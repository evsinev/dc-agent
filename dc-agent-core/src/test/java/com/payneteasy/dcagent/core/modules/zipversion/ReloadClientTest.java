package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class ReloadClientTest {

    private HttpServer   server;
    private ReloadClient client;
    private final AtomicReference<Handler> handler = new AtomicReference<>();

    interface Handler {
        void handle(HttpExchange aExchange) throws Exception;
    }

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                handler.get().handle(exchange);
            } catch (Exception e) {
                // the client went away
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new ReloadClient();
    }

    @After
    public void tearDown() {
        client.close();
        server.stop(0);
    }

    private ZipArchiveVersionSettings settings(String aWait, Map<String, String> aHeaders, String aBody) {
        return ZipArchiveVersionSettings.from("bundle", TZipArchiveVersionConfig.builder()
                .dir("/opt/app/bundles")
                .versionFile("/opt/app/bundles/current")
                .reloadUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/reload?version=${version}")
                .reloadHeaders(aHeaders)
                .reloadBody(aBody)
                .waitTimeout(aWait)
                .build(), Duration.ofMinutes(10));
    }

    private static void answer(HttpExchange aExchange, int aStatus, String aBody) throws IOException {
        byte[] bytes = aBody.getBytes(UTF_8);
        aExchange.sendResponseHeaders(aStatus, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = aExchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Test
    public void the_service_gets_the_version_headers_and_body() {
        AtomicReference<String> seen = new AtomicReference<>();
        handler.set(exchange -> {
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            answer(exchange, 200, "active v0.4.1\n");
        });

        ReloadClient.Outcome outcome = client.reload(settings("5s", Map.of("Authorization", "Bearer t"), "{\"v\":\"${version}\"}"), "v0.4.1");

        assertThat(outcome).isEqualTo(new ReloadClient.Answered(200, "active v0.4.1"));
        assertThat(((ReloadClient.Answered) outcome).confirmed()).isTrue();
        assertThat(seen.get()).isEqualTo("POST /reload?version=v0.4.1 Bearer t {\"v\":\"v0.4.1\"}");
    }

    @Test
    public void a_refusal_carries_the_first_line_without_control_characters() {
        handler.set(exchange -> answer(exchange, 422, "template mail/x.txt: \u0007bad\r\nstack trace\n..."));

        assertThat(client.reload(settings("5s", null, null), "v1"))
                .isEqualTo(new ReloadClient.Answered(422, "template mail/x.txt: bad"));
    }

    @Test
    public void a_long_fast_body_is_capped() {
        handler.set(exchange -> answer(exchange, 200, "x".repeat(100_000)));

        ReloadClient.Answered answered = (ReloadClient.Answered) client.reload(settings("5s", null, null), "v1");

        assertThat(answered.status()).isEqualTo(200);
        assertThat(answered.reason()).hasSize(ReloadClient.BODY_LIMIT);
    }

    /** At the limit the subscription is cancelled: the connection closes, the service's next writes fail. */
    @Test
    public void reaching_the_limit_closes_the_exchange() throws Exception {
        CountDownLatch writeFailed = new CountDownLatch(1);
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                out.write("y".repeat(ReloadClient.BODY_LIMIT + 50).getBytes(UTF_8));
                out.flush();
                for (int i = 0; i < 100; i++) {
                    Thread.sleep(100);
                    out.write('z');
                    out.flush();
                }
            } catch (IOException e) {
                writeFailed.countDown();
            }
        });

        ReloadClient.Answered answered = (ReloadClient.Answered) client.reload(settings("30s", null, null), "v1");

        assertThat(answered.reason()).hasSize(ReloadClient.BODY_LIMIT);
        assertThat(writeFailed.await(5, TimeUnit.SECONDS)).as("the connection was closed at the limit").isTrue();
    }

    @Test
    public void a_redirect_is_not_followed() {
        handler.set(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.com/");
            answer(exchange, 302, "");
        });

        assertThat(client.reload(settings("5s", null, null), "v1")).isEqualTo(new ReloadClient.Answered(302, ""));
    }

    @Test
    public void a_silent_service_times_out_at_wait_timeout() {
        CountDownLatch release = new CountDownLatch(1);
        handler.set(exchange -> release.await(30, TimeUnit.SECONDS));
        try {
            long start = System.nanoTime();
            ReloadClient.Outcome outcome = client.reload(settings("1s", null, null), "v1");
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(outcome).isInstanceOf(ReloadClient.TimedOut.class);
            assertThat(took).isBetween(900L, 3000L);
        } finally {
            release.countDown();
        }
    }

    /**
     * A body dripping one byte at a time: the whole exchange is bounded, the connection is really
     * closed (the service sees its write fail soon — not exactly at the deadline, the JDK releases
     * asynchronously) and the shared client still works afterwards.
     */
    @Test
    public void a_dripping_service_is_cut_at_wait_timeout_and_the_client_keeps_working() throws Exception {
        CountDownLatch writeFailed = new CountDownLatch(1);
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                for (int i = 0; i < 120; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(250);
                }
            } catch (IOException e) {
                writeFailed.countDown();
            }
        });

        long start = System.nanoTime();
        ReloadClient.Outcome outcome = client.reload(settings("1s", null, null), "v1");
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(outcome).isInstanceOf(ReloadClient.TimedOut.class);
        assertThat(took).isBetween(900L, 3000L);
        assertThat(writeFailed.await(10, TimeUnit.SECONDS)).as("the service saw the connection closed").isTrue();

        handler.set(exchange -> answer(exchange, 200, "ok"));
        assertThat(client.reload(settings("5s", null, null), "v2")).isEqualTo(new ReloadClient.Answered(200, "ok"));
    }

    @Test
    public void a_refused_connection_is_a_failure_with_the_type_only() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ZipArchiveVersionSettings settings = ZipArchiveVersionSettings.from("bundle", TZipArchiveVersionConfig.builder()
                .dir("/opt/app/bundles")
                .versionFile("/opt/app/bundles/current")
                .reloadUrl("http://127.0.0.1:" + port + "/reload")
                .waitTimeout("5s")
                .build(), Duration.ofMinutes(10));

        ReloadClient.Outcome outcome = client.reload(settings, "v1");

        assertThat(outcome).isInstanceOf(ReloadClient.Failed.class);
        assertThat(((ReloadClient.Failed) outcome).cause()).doesNotContain(" ");
    }

    @Test
    public void first_line() {
        assertThat(ReloadClient.firstLine(null)).isEmpty();
        assertThat(ReloadClient.firstLine("  ok  ")).isEqualTo("ok");
        assertThat(ReloadClient.firstLine("a\rb")).isEqualTo("a");
        assertThat(ReloadClient.firstLine("a\u0000b\tc\nd")).isEqualTo("abc");
    }
}
