package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.TestConfigs;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.daemontools.Supervise;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A JAR deploy against a supervised service (real fifos, {@link FakeSupervise}): the agent never
 * leaves a service down that it stopped itself, and a failure is answered 417, not 500.
 */
public class JarDeployRecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path       root;
    private Path       jar;
    private HttpServer health;

    @Before
    public void setUp() throws Exception {
        root = tmp.getRoot().toPath().toRealPath();
        jar  = Files.createDirectories(root.resolve("opt/app")).resolve("app.jar");
        Files.writeString(jar, "old");
        health = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        health.createContext("/health", aExchange -> {
            aExchange.sendResponseHeaders(200, -1);
            aExchange.close();
        });
        health.start();
    }

    @After
    public void tearDown() {
        health.stop(0);
    }

    @Test
    public void a_deploy_replaces_the_jar_between_the_stop_and_the_start() throws Exception {
        try (FakeSupervise fake = service(42).onCommand(c -> c == 'd' ? 0 : c == 'u' ? 77 : -1, 200).start()) {
            Answer answer = deploy(config("5s", "5s").build());

            assertThat(answer.status.get()).isEqualTo(200);
            assertThat(fake.commands()).containsExactly('d', 'u');
            assertThat(jar).hasContent("new");
            assertThat(fake.pid()).isEqualTo(77);
        }
    }

    @Test
    public void a_service_that_does_not_stop_in_time_is_started_again() throws Exception {
        try (FakeSupervise fake = service(42).start()) { // ignores the commands: the pid stays
            Answer answer = deploy(config("1s", "5s").build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(answer.body.toString()).contains("is not down after 1 s");
            assertThat(fake.commands()).containsExactly('d', 'u');
            assertThat(jar).hasContent("old");
        }
    }

    @Test
    public void a_log_that_cannot_be_read_is_reported_and_the_answer_is_still_417() throws Exception {
        try (FakeSupervise fake = service(42).start()) {
            Answer answer = deploy(config("1s", "5s").serviceLogFile(root.resolve("no-such/current").toString()).build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(answer.body.toString()).contains("Cannot read the log " + root.resolve("no-such/current"));
        }
    }

    @Test
    public void the_start_waits_for_serviceStartTimeout_not_serviceStopTimeout() throws Exception {
        try (FakeSupervise fake = service(42).onCommand(c -> c == 'd' ? 0 : -1, 0).start()) { // never starts
            long started = System.nanoTime();

            Answer answer = deploy(config("5m", "1s").build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(answer.body.toString()).contains("is not up after 1 s");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
            assertThat(fake.commands()).containsExactly('d', 'u'); // started once, not asked again
            assertThat(jar).hasContent("new");
        }
    }

    @Test
    public void without_supervise_the_deploy_fails_at_once_and_leaves_the_jar() throws Exception {
        try (FakeSupervise fake = service(42)) { // fifos, nobody reading them
            long started = System.nanoTime();

            Answer answer = deploy(config("5m", "5m").build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(answer.body.toString()).contains("supervise is not running");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
            assertThat(jar).hasContent("old");
        }
    }

    @Test
    public void a_bad_stop_timeout_fails_before_the_service_is_touched() throws Exception {
        try (FakeSupervise fake = service(42).start()) {
            Answer answer = deploy(config("forever", "5s").build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(fake.commands().poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull(); // no d, no u
            assertThat(jar).hasContent("old");
        }
    }

    @Test
    public void a_bad_start_timeout_fails_before_the_service_is_stopped() throws Exception {
        try (FakeSupervise fake = service(42).onCommand(c -> c == 'd' ? 0 : -1, 0).start()) {
            Answer answer = deploy(config("5s", "soon").build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(fake.commands().poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
            assertThat(fake.pid()).isEqualTo(42);
            assertThat(jar).hasContent("old");
        }
    }

    @Test
    public void a_status_of_another_supervisor_stops_the_deploy_before_the_stop() throws Exception {
        try (FakeSupervise fake = service(42).start()) {
            Files.write(root.resolve("services/app/supervise/status"), new byte[20]); // runit, daemontools-encore

            Answer answer = deploy(config("5s", "5s").build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(answer.body.toString()).contains("not 18");
            assertThat(fake.commands().poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
            assertThat(jar).hasContent("old");
        }
    }

    @Test
    public void without_a_wait_url_the_wait_is_skipped() throws Exception {
        try (FakeSupervise fake = service(42).onCommand(c -> c == 'd' ? 0 : c == 'u' ? 77 : -1, 100).start()) {
            long started = System.nanoTime();

            Answer answer = deploy(config("5s", "5s").waitUrl(null).waitDuration("3m").build());

            assertThat(answer.status.get()).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
            assertThat(jar).hasContent("new");
        }
    }

    @Test
    public void a_stop_stuck_in_a_hung_supervise_is_followed_by_the_start_whenever_it_reads_again() throws Exception {
        try (FakeSupervise fake = service(42).startStuck()) {
            Answer answer = deploy(config("5s", "5s").build());

            assertThat(answer.status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);
            assertThat(answer.body.toString()).contains("may still act on it");
            assertThat(jar).hasContent("old");

            fake.resume(); // after the answer: the late d must not leave the service down
            assertThat(fake.commands().poll(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo('d');
            assertThat(fake.commands().poll(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo('u');
        }
    }

    @Test
    public void a_second_deploy_of_the_same_service_is_refused_while_the_first_runs() throws Exception {
        try (FakeSupervise fake = service(42).startStuck()) {
            java.util.concurrent.atomic.AtomicReference<Answer> first = new java.util.concurrent.atomic.AtomicReference<>();
            Thread a = new Thread(() -> {
                try {
                    first.set(deploy(config("5s", "5s").build()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            a.start();
            Thread.sleep(300); // A is in its stop (stuck)

            // the same service under another name: /service/app -> /etc/service.d/app
            Path link = Files.createSymbolicLink(Files.createDirectories(root.resolve("links")).resolve("app"), root.resolve("services/app"));
            Answer second = deploy(config("5s", "5s").serviceDir(link.toString()).build());
            a.join(30_000);

            assertThat(second.status.get()).isEqualTo(HttpServletResponse.SC_CONFLICT);
            assertThat(second.body.toString()).contains("Another deploy of service");
            assertThat(first.get().status.get()).isEqualTo(HttpServletResponse.SC_EXPECTATION_FAILED);

            fake.resume(); // only A's commands: its d and the rollback u
            assertThat(fake.commands().poll(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo('d');
            assertThat(fake.commands().poll(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo('u');
            assertThat(fake.commands().poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    public void an_error_after_the_stop_still_starts_the_service_and_is_rethrown() throws Exception {
        try (FakeSupervise fake = service(42).onCommand(c -> c == 'd' ? 0 : -1, 0).start()) {
            DaemontoolsServiceImpl failing = new DaemontoolsServiceImpl(
                    new Supervise(Duration.ofMillis(500)), (aFormat, aArgs) -> { }, Duration.ofMillis(50)) {
                @Override
                public void waitUntil(java.io.File aServiceDir, boolean aUp, Duration aTimeout) {
                    throw new OutOfMemoryError("test");
                }
            };
            FetchUrlServlet.JarServlet servlet = new FetchUrlServlet.JarServlet(configService(config("5s", "5s").build()), failing);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> servlet.doPost(request(), response(new Answer())))
                    .isInstanceOf(OutOfMemoryError.class).hasMessage("test");
            assertThat(fake.commands().poll(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo('d');
            assertThat(fake.commands().poll(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo('u');
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private FakeSupervise service(long aPid) throws Exception {
        return new FakeSupervise(root.resolve("services/app"), aPid);
    }

    private TJarConfig.TJarConfigBuilder config(String aStopTimeout, String aStartTimeout) throws Exception {
        Path log = Files.createDirectories(root.resolve("log")).resolve("current");
        Files.writeString(log, "started\n");
        return TJarConfig.builder()
                .type(TaskType.JAR)
                .jarFilename(jar.toString())
                .serviceDir(root.resolve("services/app").toString())
                .serviceLogFile(log.toString())
                .serviceStopTimeout(aStopTimeout)
                .serviceStartTimeout(aStartTimeout)
                .waitUrl("http://127.0.0.1:" + health.getAddress().getPort() + "/health")
                .waitDuration("10s")
                .apiKeys(Map.of(TestConfigs.hash("good-key"), "ci"));
    }

    private static final class Answer {
        final AtomicInteger status = new AtomicInteger();
        final StringWriter  body   = new StringWriter();
    }

    private Answer deploy(TJarConfig aConfig) throws Exception {
        DaemontoolsServiceImpl daemontools = new DaemontoolsServiceImpl(
                new Supervise(Duration.ofMillis(500)), (aFormat, aArgs) -> { }, Duration.ofMillis(50));
        FetchUrlServlet.JarServlet servlet = new FetchUrlServlet.JarServlet(configService(aConfig), daemontools);
        Answer answer = new Answer();
        servlet.doPost(request(), response(answer));
        return answer;
    }

    private static IConfigService configService(TJarConfig aConfig) {
        return (IConfigService) Proxy.newProxyInstance(
                JarDeployRecoveryTest.class.getClassLoader(),
                new Class[]{IConfigService.class},
                (proxy, method, args) -> {
                    if ("getJarConfig".equals(method.getName()) && "app".equals(args[0])) {
                        return aConfig;
                    }
                    if ("getKeysDir".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static HttpServletRequest request() {
        ByteArrayInputStream body = new ByteArrayInputStream("new".getBytes(UTF_8));
        ServletInputStream   in   = new ServletInputStream() {
            @Override public int read()                               { return body.read(); }
            @Override public boolean isFinished()                     { return body.available() == 0; }
            @Override public boolean isReady()                        { return true; }
            @Override public void setReadListener(ReadListener aList) { throw new UnsupportedOperationException(); }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                JarDeployRecoveryTest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI"  -> "/dc-agent/jar/app";
                    case "getHeader"      -> "api-key".equals(args[0]) ? "good-key" : null;
                    case "getInputStream" -> in;
                    default               -> null;
                });
    }

    private static HttpServletResponse response(Answer aAnswer) {
        PrintWriter writer = new PrintWriter(aAnswer.body);
        return (HttpServletResponse) Proxy.newProxyInstance(
                JarDeployRecoveryTest.class.getClassLoader(),
                new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> aAnswer.status.set((Integer) args[0]);
                        case "getWriter" -> {
                            return writer;
                        }
                        default -> { }
                    }
                    return null;
                });
    }
}
