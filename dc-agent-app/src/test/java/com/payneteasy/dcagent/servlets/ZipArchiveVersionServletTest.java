package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.exception.ProblemException;
import com.payneteasy.dcagent.core.modules.zipversion.DirLocks;
import com.payneteasy.dcagent.core.modules.zipversion.Durability;
import com.payneteasy.dcagent.core.modules.zipversion.ReloadClient;
import com.payneteasy.dcagent.core.modules.zipversion.UploadTempFiles;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** The whole call against a real directory and a stub service: the outcomes of issue #98. */
public class ZipArchiveVersionServletTest {

    private static final Duration IDLE = Duration.ofMinutes(10);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path         root;
    private Path         config;
    private Path         dir;
    private Path         uploadDir;
    private Path         pointer;
    private HttpServer   service;
    private ReloadClient reloadClient;
    private Semaphore    permits;
    private FailingDurability durability;
    private ZipArchiveVersionServlet servlet;

    private final AtomicReference<Handler> handler  = new AtomicReference<>();
    private final List<String>             reloaded = Collections.synchronizedList(new ArrayList<>());

    interface Handler {
        void handle(HttpExchange aExchange) throws Exception;
    }

    @Before
    public void setUp() throws IOException {
        root      = tmp.getRoot().toPath().toRealPath();
        config    = Files.createDirectories(root.resolve("config"));
        uploadDir = Files.createDirectories(root.resolve("uploads"));
        dir       = root.resolve("bundles");
        pointer   = dir.resolve("current");

        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.setExecutor(Executors.newCachedThreadPool());
        service.createContext("/", exchange -> {
            try {
                reloaded.add(exchange.getRequestURI().getQuery());
                handler.get().handle(exchange);
            } catch (Exception e) {
                // the agent went away
            } finally {
                exchange.close();
            }
        });
        service.start();
        handler.set(exchange -> answer(exchange, 200, "active"));

        writeConfig("bundle", "5s", "http://127.0.0.1:" + service.getAddress().getPort() + "/reload?version=${version}");
        reloadClient = new ReloadClient();
        permits      = new Semaphore(ZipArchiveVersionServlet.UPLOADS_IN_FLIGHT);
        durability   = new FailingDurability();
        servlet      = servlet(new DirLocks(Duration.ofSeconds(5)));
    }

    @After
    public void tearDown() {
        reloadClient.close();
        service.stop(0);
    }

    private ZipArchiveVersionServlet servlet(DirLocks aLocks) {
        return new ZipArchiveVersionServlet(new ConfigServiceImpl(config.toFile(), Gsons.PRETTY_GSON), IDLE, reloadClient,
                new UploadTempFiles(uploadDir), permits, aLocks, durability);
    }

    private void writeConfig(String aName, String aWait, String aReloadUrl) throws IOException {
        Files.writeString(config.resolve(aName + ".json"), "{\"type\":\"ZIP_ARCHIVE_VERSION\",\"apiKeys\":{\"good-key\":\"ci\"},"
                + "\"dir\":" + Gsons.PRETTY_GSON.toJson(dir.toString()) + ","
                + "\"versionFile\":" + Gsons.PRETTY_GSON.toJson(pointer.toString()) + ","
                + "\"maxUploadBytes\":\"64k\",\"waitTimeout\":\"" + aWait + "\","
                + "\"reloadUrl\":" + Gsons.PRETTY_GSON.toJson(aReloadUrl) + "}");
    }

    // ── Outcomes ─────────────────────────────────────────────────────────

    @Test
    public void confirmed_publishes_switches_and_answers_one_line() throws IOException {
        Result result = post("v1", zip("index.html", "<h1>"));

        assertThat(result.status).isEqualTo(200);
        assertThat(result.body).matches("published active v1 sha256 [0-9a-f]{64}\n");
        assertThat(pointer).hasContent("v1\n");
        assertThat(dir.resolve("v1/index.html")).hasContent("<h1>");
        assertThat(reloaded).containsExactly("version=v1");
        // confirmed: .previous is removed (question A), the upload is gone
        assertThat(Files.exists(dir.resolve(".current.previous"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(listing(uploadDir)).isEmpty();
    }

    @Test
    public void a_retry_of_the_same_tag_is_present_and_still_asks_the_service() throws IOException {
        String first  = post("v1", zip("a.txt", "1")).body;
        Result second = post("v1", zip("a.txt", "1"));

        assertThat(second.status).isEqualTo(200);
        assertThat(second.body).isEqualTo(first.replace("published", "present"));
        assertThat(reloaded).containsExactly("version=v1", "version=v1");
    }

    @Test
    public void other_contents_under_a_published_tag_are_a_409_and_the_service_is_not_asked() throws IOException {
        post("v1", zip("a.txt", "1"));
        reloaded.clear();

        Result result = post("v1", zip("a.txt", "2"));

        assertThat(result.status).isEqualTo(409);
        assertThat(reloaded).isEmpty();
        assertThat(dir.resolve("v1/a.txt")).hasContent("1");
    }

    @Test
    public void a_refusal_of_the_service_is_relayed_and_the_pointer_goes_back() throws IOException {
        post("v1", zip("a.txt", "1"));
        for (int status : new int[]{409, 422, 503}) {
            handler.set(exchange -> answer(exchange, status, "template mail/x: bad\nstack"));

            Result result = post("v2", zip("a.txt", "2"));

            assertThat(result.status).as("service " + status).isEqualTo(status);
            assertThat(result.message).contains("template mail/x: bad").contains("pointer back to v1").doesNotContain("stack");
            assertThat(pointer).hasContent("v1\n");
            // the version directory stays in every failure
            assertThat(dir.resolve("v2/a.txt")).hasContent("2");
        }
    }

    @Test
    public void the_first_publication_refused_removes_the_pointer() throws IOException {
        handler.set(exchange -> answer(exchange, 422, "no"));

        Result result = post("v1", zip("a.txt", "1"));

        assertThat(result.status).isEqualTo(422);
        assertThat(result.message).contains("pointer removed");
        assertThat(Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    public void any_other_status_or_a_refused_connection_is_a_502_and_the_pointer_goes_back() throws IOException {
        post("v1", zip("a.txt", "1"));
        handler.set(exchange -> answer(exchange, 500, "boom"));

        assertThat(post("v2", zip("a.txt", "2")).status).isEqualTo(502);
        assertThat(pointer).hasContent("v1\n");

        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        writeConfig("bundle", "5s", "http://127.0.0.1:" + closed + "/reload?version=${version}");
        Result refused = post("v3", zip("a.txt", "3"));
        assertThat(refused.status).isEqualTo(502);
        assertThat(refused.message).contains("reload call failed");
        assertThat(pointer).hasContent("v1\n");
    }

    @Test
    public void a_pointer_changed_by_hand_during_the_wait_is_not_touched() throws IOException {
        post("v1", zip("a.txt", "1"));
        handler.set(exchange -> {
            Files.writeString(pointer, "v9\n");
            answer(exchange, 422, "no");
        });

        Result result = post("v2", zip("a.txt", "2"));

        assertThat(result.status).isEqualTo(422);
        assertThat(result.message).contains("changed meanwhile to v9");
        assertThat(pointer).hasContent("v9\n");
    }

    @Test
    public void a_silent_service_is_a_504_at_wait_timeout_and_the_pointer_goes_back() throws IOException {
        writeConfig("bundle", "1s", "http://127.0.0.1:" + service.getAddress().getPort() + "/reload?version=${version}");
        post("v1", zip("a.txt", "1"));
        CountDownLatch release = new CountDownLatch(1);
        handler.set(exchange -> release.await(30, TimeUnit.SECONDS));
        try {
            long   start  = System.nanoTime();
            Result result = post("v2", zip("a.txt", "2"));

            assertThat(result.status).isEqualTo(504);
            assertThat(result.message).contains("unknown").contains("pointer back to v1");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(4000);
            assertThat(pointer).hasContent("v1\n");
        } finally {
            release.countDown();
        }
    }

    @Test
    public void a_dripping_service_is_a_504_at_wait_timeout_and_releases_the_lock() throws Exception {
        writeConfig("bundle", "1s", "http://127.0.0.1:" + service.getAddress().getPort() + "/reload?version=${version}");
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < 120; i++) {
                out.write('x');
                out.flush();
                Thread.sleep(250);
            }
        });

        long   start  = System.nanoTime();
        Result result = post("v1", zip("a.txt", "1"));
        assertThat(result.status).isEqualTo(504);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(4000);

        // the lock is free: another call into the same dir proceeds at once
        handler.set(exchange -> answer(exchange, 200, "ok"));
        ZipArchiveVersionServlet impatient = servlet(new DirLocks(Duration.ofMillis(200)));
        assertThat(post(impatient, "v2", zip("a.txt", "2"), -1).status).isEqualTo(200);
    }

    @Test
    public void a_late_retry_of_an_older_tag_switches_the_pointer_back_to_it() throws IOException {
        post("v1", zip("a.txt", "1"));
        post("v2", zip("a.txt", "2"));

        Result result = post("v1", zip("a.txt", "1"));

        assertThat(result.body).startsWith("present active v1");
        assertThat(pointer).hasContent("v1\n");
    }

    /** Question A, decided by the user: a confirmed version is not rolled back past by a failed retry. */
    @Test
    public void a_failed_retry_of_a_confirmed_version_leaves_the_pointer_at_it() throws IOException {
        post("v1", zip("a.txt", "1"));
        post("v2", zip("a.txt", "2"));
        handler.set(exchange -> answer(exchange, 503, "overloaded"));

        Result result = post("v2", zip("a.txt", "2"));

        assertThat(result.status).isEqualTo(503);
        assertThat(result.message).contains("pointer left at v2");
        assertThat(pointer).hasContent("v2\n");
    }

    @Test
    public void a_cut_connection_while_the_answer_is_written_rolls_nothing_back() throws IOException {
        Throwable thrown = null;
        try {
            servlet.doPost(request("/dc-agent/zip-archive-version/bundle/v1", zip("a.txt", "1"), -1, new AtomicInteger()),
                    brokenResponse());
        } catch (IOException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull();
        assertThat(pointer).hasContent("v1\n");
    }

    // ── The lock is held for the whole call ──────────────────────────────

    @Test
    public void a_second_call_into_the_same_dir_waits_for_the_reload_of_the_first() throws Exception {
        CountDownLatch inReload = new CountDownLatch(1);
        CountDownLatch release  = new CountDownLatch(1);
        handler.set(exchange -> {
            if (exchange.getRequestURI().getQuery().endsWith("v1")) {
                inReload.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            answer(exchange, 200, "ok");
        });

        CompletableFuture<Result> first = CompletableFuture.supplyAsync(() -> postQuietly("v1"));
        assertThat(inReload.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Result> second = CompletableFuture.supplyAsync(() -> postQuietly("v2"));
        Thread.sleep(500);
        assertThat(second).isNotDone();

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).status).isEqualTo(200);
        assertThat(second.get(10, TimeUnit.SECONDS).status).isEqualTo(200);
        assertThat(reloaded).containsExactly("version=v1", "version=v2");
        assertThat(pointer).hasContent("v2\n");
    }

    @Test
    public void a_lock_held_longer_than_the_wait_is_a_503() throws Exception {
        CountDownLatch inReload = new CountDownLatch(1);
        CountDownLatch release  = new CountDownLatch(1);
        handler.set(exchange -> {
            inReload.countDown();
            release.await(10, TimeUnit.SECONDS);
            answer(exchange, 200, "ok");
        });
        CompletableFuture<Result> first = CompletableFuture.supplyAsync(() -> postQuietly("v1"));
        try {
            assertThat(inReload.await(5, TimeUnit.SECONDS)).isTrue();

            Result busy = post(servlet(new DirLocks(Duration.ofMillis(300))), "v2", zip("a.txt", "2"), -1);

            assertThat(busy.status).isEqualTo(503);
            assertThat(busy.message).startsWith("busy");
            // the 503 freed its permit; the first call gave its own back before the reload
            assertThat(permits.availablePermits()).isEqualTo(2);
        } finally {
            release.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS).status).isEqualTo(200);
        assertThat(permits.availablePermits()).isEqualTo(2);
    }

    // ── Uploads: permits, temp files, limits ─────────────────────────────

    @Test
    public void a_third_upload_is_a_503_before_the_body_is_read() throws IOException {
        permits.acquireUninterruptibly(2);
        try {
            AtomicInteger reads  = new AtomicInteger();
            Result        result = post(servlet, "v1", zip("a.txt", "1"), -1, reads);

            assertThat(result.status).isEqualTo(503);
            assertThat(reads.get()).isZero();
            assertThat(Files.exists(dir)).isFalse();
        } finally {
            permits.release(2);
        }
    }

    @Test
    public void during_the_reload_the_permit_is_free_and_the_upload_is_gone() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        handler.set(exchange -> {
            seen.set(permits.availablePermits() + " permits, uploads " + listing(uploadDir));
            answer(exchange, 200, "ok");
        });

        post("v1", zip("a.txt", "1"));

        assertThat(seen.get()).isEqualTo("2 permits, uploads []");
    }

    @Test
    public void an_upload_over_max_upload_bytes_is_a_413_by_header_or_by_count() throws IOException {
        byte[] big = zip("big.bin", "x".repeat(100_000), false);
        assertThat(big.length).isGreaterThan(64 * 1024);

        AtomicInteger reads    = new AtomicInteger();
        Result        declared = post(servlet, "v1", big, big.length, reads);
        assertThat(declared.status).isEqualTo(413);
        assertThat(reads.get()).isZero();

        Result counted = post(servlet, "v1", big, -1, new AtomicInteger());
        assertThat(counted.status).isEqualTo(413);

        assertThat(Files.exists(dir)).isFalse();
        assertThat(listing(uploadDir)).isEmpty();
        assertThat(permits.availablePermits()).isEqualTo(2);
    }

    @Test
    public void a_refused_archive_is_a_400_creates_nothing_and_frees_the_permit() throws IOException {
        Result result = post("v1", zip("../evil.txt", "x"));

        assertThat(result.status).isEqualTo(400);
        assertThat(Files.exists(dir)).isFalse();
        assertThat(listing(uploadDir)).isEmpty();
        assertThat(permits.availablePermits()).isEqualTo(2);
    }

    // ── Path, names, config ──────────────────────────────────────────────

    @Test
    public void other_path_shapes_and_bad_versions_are_a_400_after_the_key_and_create_nothing() throws IOException {
        for (String uri : new String[]{"/dc-agent/zip-archive-version/bundle", "/dc-agent/zip-archive-version/bundle/",
                "/dc-agent/zip-archive-version/bundle//v1", "/dc-agent/zip-archive-version/bundle/v1/",
                "/dc-agent/zip-archive-version/bundle/v1/x", "/dc-agent/zip-archive-version/bundle/..",
                "/dc-agent/zip-archive-version/bundle/.v1", "/dc-agent/zip-archive-version/bundle/current",
                "/dc-agent/zip-archive-version/bundle/v%2E1", "/dc-agent/zip-archive-version/bundle/a\\b"}) {
            Result result = postUri(servlet, uri, zip("a.txt", "1"), -1, new AtomicInteger());
            assertThat(result.status).as(uri).isEqualTo(400);
        }
        assertThat(Files.exists(dir)).isFalse();
        assertThat(reloaded).isEmpty();
    }

    @Test
    public void the_path_is_parsed_after_the_exact_context_and_endpoint() {
        assertThat(ZipArchiveVersionServlet.Target.parse("/dc-agent", "/dc-agent/zip-archive-version/bundle/v1"))
                .isEqualTo(new ZipArchiveVersionServlet.Target("bundle", "v1"));
        // a context named like the endpoint does not shift the command name
        assertThat(ZipArchiveVersionServlet.Target.parse("/zip-archive-version", "/zip-archive-version/zip-archive-version/bundle/v1"))
                .isEqualTo(new ZipArchiveVersionServlet.Target("bundle", "v1"));
        assertThat(ZipArchiveVersionServlet.Target.parse("", "/zip-archive-version/bundle/v1"))
                .isEqualTo(new ZipArchiveVersionServlet.Target("bundle", "v1"));
        assertThat(ZipArchiveVersionServlet.Target.parse("/dc-agent", "/other/zip-archive-version/bundle/v1"))
                .isEqualTo(new ZipArchiveVersionServlet.Target(null, null));
        assertThat(ZipArchiveVersionServlet.Target.parse("/dc-agent", "/dc-agent/zip-archive-version/bundle//v1"))
                .isEqualTo(new ZipArchiveVersionServlet.Target("bundle", null));
    }

    @Test
    public void a_rollback_that_cannot_be_written_is_a_500_naming_the_pointer() throws IOException {
        Assume.assumeFalse("root ignores directory permissions", "root".equals(System.getProperty("user.name")));
        post("v1", zip("a.txt", "1"));
        handler.set(exchange -> {
            // the disk refuses writes into dir while the service answers
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            answer(exchange, 422, "no");
        });
        try {
            Result result = post("v2", zip("a.txt", "2"));

            assertThat(result.status).isEqualTo(500);
            assertThat(result.message).startsWith("rollback failed, pointer names v2").contains("answered 422");
            assertThat(pointer).hasContent("v2\n");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    public void a_bad_config_value_is_a_500_naming_the_field_after_the_key() throws IOException {
        writeConfig("bundle", "1h", "http://127.0.0.1:1/reload");

        Result result = post("v1", zip("a.txt", "1"));

        assertThat(result.status).isEqualTo(500);
        assertThat(result.message).startsWith("config bundle: field waitTimeout:");
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    public void dir_is_created_by_the_first_call() throws IOException {
        assertThat(Files.exists(dir)).isFalse();

        assertThat(post("v1", zip("a.txt", "1")).status).isEqualTo(200);
        assertThat(dir).isDirectory();
    }

    // ── fsync failures ───────────────────────────────────────────────────

    @Test
    public void a_failed_sync_after_the_version_or_the_pointer_is_a_500_and_a_retry_settles_it() throws IOException {
        for (String mark : new String[]{"version-moved", "pointer-moved"}) {
            String version = mark.startsWith("version") ? "v1" : "v2";
            durability.failOn(mark);

            Result failed = post(version, zip("a.txt", version));
            assertThat(failed.status).as(mark).isEqualTo(500);
            assertThat(failed.message).as(mark).startsWith("durability not confirmed");

            Result retry = post(version, zip("a.txt", version));
            assertThat(retry.status).as(mark).isEqualTo(200);
            assertThat(retry.body).as(mark).startsWith("present active " + version);
            assertThat(pointer).hasContent(version + "\n");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static final class Result {
        int    status;
        String body    = "";
        String message = "";
    }

    private Result post(String aVersion, byte[] aZip) {
        return post(servlet, aVersion, aZip, -1);
    }

    private Result postQuietly(String aVersion) {
        try {
            return post(aVersion, zip("a.txt", aVersion));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Result post(ZipArchiveVersionServlet aServlet, String aVersion, byte[] aZip, long aContentLength) {
        return postUri(aServlet, "/dc-agent/zip-archive-version/bundle/" + aVersion, aZip, aContentLength, new AtomicInteger());
    }

    private Result post(ZipArchiveVersionServlet aServlet, String aVersion, byte[] aZip, long aContentLength, AtomicInteger aReads) {
        return postUri(aServlet, "/dc-agent/zip-archive-version/bundle/" + aVersion, aZip, aContentLength, aReads);
    }

    /** As ErrorFilter would answer: a ProblemException's code and message, a 500 for anything else. */
    private Result postUri(ZipArchiveVersionServlet aServlet, String aUri, byte[] aZip, long aContentLength, AtomicInteger aReads) {
        Result       result = new Result();
        StringWriter body   = new StringWriter();
        try {
            aServlet.doPost(request(aUri, aZip, aContentLength, aReads), response(result, body));
            result.body = body.toString();
        } catch (ProblemException e) {
            result.status  = e.getHttpCode();
            result.message = e.getMessage();
        } catch (Exception e) {
            result.status  = 500;
            result.message = e.toString();
        }
        return result;
    }

    private static HttpServletRequest request(String aUri, byte[] aBody, long aContentLength, AtomicInteger aReads) {
        ByteArrayInputStream body = new ByteArrayInputStream(aBody);
        ServletInputStream   in   = new ServletInputStream() {
            @Override public int read()                               { return body.read(); }
            @Override public int read(byte[] b, int off, int len)     { return body.read(b, off, len); }
            @Override public boolean isFinished()                     { return body.available() == 0; }
            @Override public boolean isReady()                        { return true; }
            @Override public void setReadListener(ReadListener aList) { throw new UnsupportedOperationException(); }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                ZipArchiveVersionServletTest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI"        -> aUri;
                    case "getContextPath"       -> "/dc-agent";
                    case "getHeader"            -> "api-key".equals(args[0]) ? "good-key" : null;
                    case "getContentLengthLong" -> aContentLength;
                    case "getInputStream"       -> {
                        aReads.incrementAndGet();
                        yield in;
                    }
                    default                     -> null;
                });
    }

    private static HttpServletResponse response(Result aResult, StringWriter aBody) {
        PrintWriter writer = new PrintWriter(aBody);
        return (HttpServletResponse) Proxy.newProxyInstance(
                ZipArchiveVersionServletTest.class.getClassLoader(),
                new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setStatus" -> {
                        aResult.status = (Integer) args[0];
                        yield null;
                    }
                    case "getWriter" -> writer;
                    default          -> null;
                });
    }

    private static HttpServletResponse brokenResponse() {
        return (HttpServletResponse) Proxy.newProxyInstance(
                ZipArchiveVersionServletTest.class.getClassLoader(),
                new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("getWriter".equals(method.getName())) {
                        throw new IOException("connection reset by the client");
                    }
                    return null;
                });
    }

    private static void answer(HttpExchange aExchange, int aStatus, String aBody) throws IOException {
        byte[] bytes = aBody.getBytes(UTF_8);
        aExchange.sendResponseHeaders(aStatus, bytes.length);
        try (OutputStream out = aExchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static byte[] zip(String aName, String aContent) throws IOException {
        return zip(aName, aContent, true);
    }

    private static byte[] zip(String aName, String aContent, boolean aDeflate) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.setLevel(aDeflate ? 6 : 0);
            out.putNextEntry(new ZipEntry(aName));
            out.write(aContent.getBytes(UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static List<String> listing(Path aDir) throws IOException {
        try (Stream<Path> list = Files.list(aDir)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** Real fsyncs; the next sync with the given mark fails once. */
    private static final class FailingDurability implements Durability {

        private String failMark;

        synchronized void failOn(String aMark) {
            failMark = aMark;
        }

        @Override
        public void syncFile(FileChannel aChannel, String aMark) throws IOException {
            check(aMark);
            FS.syncFile(aChannel, aMark);
        }

        @Override
        public void syncDir(Path aDir, String aMark) throws IOException {
            check(aMark);
            FS.syncDir(aDir, aMark);
        }

        private synchronized void check(String aMark) throws IOException {
            if (aMark.equals(failMark)) {
                failMark = null;
                throw new IOException("injected fsync failure at " + aMark);
            }
        }
    }
}
