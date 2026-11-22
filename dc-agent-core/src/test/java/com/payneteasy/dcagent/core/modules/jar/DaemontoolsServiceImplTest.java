package com.payneteasy.dcagent.core.modules.jar;

import com.payneteasy.dcagent.core.daemontools.FakeSupervise;
import com.payneteasy.dcagent.core.daemontools.Supervise;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DaemontoolsServiceImplTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final DaemontoolsServiceImpl service = new DaemontoolsServiceImpl(
            new Supervise(Duration.ofMillis(500)), (aFormat, aArgs) -> { }, Duration.ofMillis(50));

    private Path dir() {
        return tmp.getRoot().toPath().resolve("app");
    }

    @Test
    public void stop_sends_d_and_waits_until_the_process_is_gone() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(dir(), 42).onCommand(c -> c == 'd' ? 0 : -1, 300).start()) {
            service.stopService(dir().toFile(), Duration.ofSeconds(5));

            assertThat(fake.commands()).containsExactly('d');
            assertThat(fake.pid()).isZero();
        }
    }

    @Test
    public void start_sends_u_and_waits_for_a_process() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(dir(), 0).onCommand(c -> c == 'u' ? 77 : -1, 300).start()) {
            service.startService(dir().toFile(), Duration.ofSeconds(5));

            assertThat(fake.commands()).containsExactly('u');
            assertThat(fake.pid()).isEqualTo(77);
        }
    }

    @Test
    public void a_service_that_does_not_stop_in_time_is_reported_with_its_state() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(dir(), 42).start()) {
            long started = System.nanoTime();

            assertThatThrownBy(() -> service.stopService(dir().toFile(), Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not down after 1 s")
                    .hasMessageContaining("up, pid 42");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    public void without_supervise_stop_fails_at_once_instead_of_waiting() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(dir(), 42)) {
            long started = System.nanoTime();

            assertThatThrownBy(() -> service.stopService(dir().toFile(), Duration.ofMinutes(5)))
                    .hasMessageContaining("supervise is not running");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    public void the_status_counts_the_seconds_since_the_change() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(dir(), 42).start()) {
            fake.writeStatus(42, System.currentTimeMillis() - 30_000);

            DaemontoolsServiceImpl.ServiceStatus status = service.getServiceStatus(new File(dir().toString()));

            assertThat(status.getStatus()).isEqualTo("up");
            assertThat(status.getPid()).isEqualTo(42);
            assertThat(status.getSeconds()).isBetween(29, 32);
        }
    }
}
