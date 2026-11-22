package com.payneteasy.dcagent.core.daemontools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeFalse;

/** {@link Supervise} against real fifos, the way daemontools' supervise serves them. */
public class SuperviseTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Supervise supervise = new Supervise(Duration.ofMillis(500));

    private Path service() {
        return tmp.getRoot().toPath().resolve("app");
    }

    @Test
    public void a_command_is_one_byte_on_the_control_fifo() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42).start()) {
            supervise.send(service(), 'd');
            supervise.send(service(), 'u');

            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('d');
            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('u');
        }
    }

    @Test
    public void the_status_is_read_while_supervise_runs() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 4711).start()) {
            assertThat(supervise.status(service()).getPid()).isEqualTo(4711);
        }
    }

    @Test
    public void without_supervise_a_command_is_refused_and_never_delivered_later() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42)) {
            long started = System.nanoTime();

            assertThatThrownBy(() -> supervise.send(service(), 'd'))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("supervise is not running");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

            fake.start(); // the opener given up on now gets its reader: it must close without writing
            assertThat(fake.commands().poll(1, TimeUnit.SECONDS)).isNull();
        }
    }

    @Test
    public void a_fifo_has_at_most_one_io_thread_even_under_parallel_calls() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42)) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                List<java.util.concurrent.Future<Throwable>> calls = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    calls.add(pool.submit(() -> {
                        try {
                            supervise.send(service(), 'd');
                            return null;
                        } catch (Throwable e) {
                            return e;
                        }
                    }));
                }
                for (java.util.concurrent.Future<Throwable> call : calls) {
                    assertThat(call.get(10, TimeUnit.SECONDS)).isInstanceOf(IOException.class)
                            .hasMessageContaining("supervise is not running");
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(ioThreads()).isEqualTo(1); // the one blocked open, not eight

            fake.start(); // the blocked open gets its reader, closes without writing and ends
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ioThreads() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(ioThreads()).isZero();
            supervise.send(service(), 'u');
            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('u'); // only u: the d calls were never delivered
            assertThat(fake.commands().poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    public void a_command_behind_a_stuck_one_reaches_supervise_after_it() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42).startStuck()) {
            assertThatThrownBy(() -> supervise.send(service(), 'd'))
                    .isInstanceOf(Supervise.CommandMayBeDelivered.class)
                    .hasMessageContaining("may still act on it");
            assertThatThrownBy(() -> supervise.send(service(), 'u')) // the rollback of a deploy
                    .isInstanceOf(Supervise.CommandMayBeDelivered.class)
                    .hasMessageContaining("queued behind the earlier one");

            fake.resume(); // supervise reads again, long after both calls gave up

            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('d');
            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('u');
            assertThat(fake.commands().poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    public void commands_behind_a_stuck_one_keep_their_order_up_to_the_limit() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42).startStuck()) {
            assertThatThrownBy(() -> supervise.send(service(), 'd')).isInstanceOf(Supervise.CommandMayBeDelivered.class); // stuck
            for (char command : "dhu".toCharArray()) { // another deploy, the control plane, the rollback
                assertThatThrownBy(() -> supervise.send(service(), command)).isInstanceOf(Supervise.CommandMayBeDelivered.class);
            }
            // reached through a link, the same service and the same queue
            Path link = Files.createSymbolicLink(tmp.getRoot().toPath().resolve("link"), service());
            assertThatThrownBy(() -> new Supervise(Duration.ofMillis(100)).send(link, 'u')).isInstanceOf(Supervise.CommandMayBeDelivered.class);

            fake.resume();

            StringBuilder received = new StringBuilder();
            for (Character c = fake.commands().poll(5, TimeUnit.SECONDS); c != null; c = fake.commands().poll(500, TimeUnit.MILLISECONDS)) {
                received.append(c);
            }
            assertThat(received.toString()).isEqualTo("ddhu"); // the u through the link is the queued u
        }
    }

    @Test
    public void a_start_sent_while_interrupted_behind_a_stuck_stop_is_queued_and_the_interrupt_kept() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42).startStuck()) {
            assertThatThrownBy(() -> supervise.send(service(), 'd')).isInstanceOf(Supervise.CommandMayBeDelivered.class);

            java.util.concurrent.atomic.AtomicReference<Throwable> thrown      = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean              interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            Supervise patient = new Supervise(Duration.ofSeconds(30)); // waits for the stuck d: interrupted meanwhile
            Thread rollback = new Thread(() -> {
                try {
                    patient.send(service(), 'u');
                } catch (Throwable e) {
                    thrown.set(e);
                }
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            rollback.start();
            Thread.sleep(300);
            rollback.interrupt();
            rollback.join(10_000);

            assertThat(thrown.get()).isInstanceOf(Supervise.CommandMayBeDelivered.class);
            assertThat(interrupted.get()).as("the interrupt is kept").isTrue();

            fake.resume();
            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('d');
            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('u');
        }
    }

    @Test
    public void an_uninterruptible_send_interrupted_before_the_fifo_opened_still_delivers() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42)) { // supervise not there yet
            java.util.concurrent.atomic.AtomicReference<Throwable> thrown      = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean              interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            Supervise patient = new Supervise(Duration.ofSeconds(30));
            Thread rollback = new Thread(() -> {
                try {
                    patient.sendUninterruptibly(service(), 'u');
                } catch (Throwable e) {
                    thrown.set(e);
                }
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            rollback.start();
            Thread.sleep(200);
            rollback.interrupt(); // before the fifo opened: an ordinary send would give up here
            Thread.sleep(200);
            fake.start();         // supervise appears
            rollback.join(10_000);

            assertThat(thrown.get()).isNull();
            assertThat(interrupted.get()).as("the interrupt is kept").isTrue();
            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('u');
        }
    }

    @Test
    public void an_ordinary_send_interrupted_before_the_fifo_opened_delivers_nothing() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42)) {
            java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
            Supervise patient = new Supervise(Duration.ofSeconds(30));
            Thread caller = new Thread(() -> {
                try {
                    patient.send(service(), 'd');
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            caller.start();
            Thread.sleep(200);
            caller.interrupt();
            caller.join(10_000);
            fake.start();

            assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
            assertThat(fake.commands().poll(500, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    public void a_full_queue_refuses_other_commands_but_always_takes_a_start() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42).startStuck()) {
            Supervise quick = new Supervise(Duration.ofMillis(50));
            assertThatThrownBy(() -> quick.send(service(), 'd')).isInstanceOf(Supervise.CommandMayBeDelivered.class);
            for (int i = 0; i < 16; i++) {
                assertThatThrownBy(() -> quick.send(service(), 'h')).isInstanceOf(Supervise.CommandMayBeDelivered.class);
            }
            assertThatThrownBy(() -> quick.send(service(), 'h'))
                    .isNotInstanceOf(Supervise.CommandMayBeDelivered.class)
                    .hasMessageContaining("still waiting");
            for (int i = 0; i < 3; i++) { // the rollback: room even now, and only once
                assertThatThrownBy(() -> quick.send(service(), 'u')).isInstanceOf(Supervise.CommandMayBeDelivered.class);
            }

            fake.resume();

            StringBuilder received = new StringBuilder();
            for (Character c = fake.commands().poll(5, TimeUnit.SECONDS); c != null; c = fake.commands().poll(500, TimeUnit.MILLISECONDS)) {
                received.append(c);
            }
            assertThat(received.toString()).isEqualTo("d" + "h".repeat(16) + "u");
        }
    }

    @Test
    public void a_caller_interrupted_while_the_command_is_being_written_is_told_it_may_be_delivered() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42).startStuck()) {
            java.util.concurrent.atomic.AtomicReference<Throwable> thrown      = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean              interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    supervise.send(service(), 'd');
                } catch (Throwable e) {
                    thrown.set(e);
                }
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ioThreads() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(100); // the fifo is open (it has a reader) and the write blocks: the pipe is full
            caller.interrupt();
            caller.join(10_000);

            // not "interrupted, so not delivered": the byte is on its way
            assertThat(thrown.get()).isInstanceOf(Supervise.CommandMayBeDelivered.class);
            assertThat(interrupted.get()).as("the interrupt is kept").isTrue();

            fake.resume();
            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('d');
        }
    }

    private long ioThreads() {
        String name;
        try {
            name = "supervise " + service().resolve("supervise").toRealPath().resolve("control");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && name.equals(t.getName())).count();
    }

    @Test
    public void a_fifo_recreated_under_the_same_path_is_not_blocked_by_a_thread_stuck_on_the_old_one() throws Exception {
        Path control = service().resolve("supervise/control");
        try (FakeSupervise old = new FakeSupervise(service(), 42)) {
            assertThatThrownBy(() -> supervise.send(service(), 'd')).hasMessageContaining("nothing reads control");
        }
        // the service is reinstalled: new fifos, the same paths; its supervise runs
        Files.delete(control);
        Files.delete(service().resolve("supervise/ok"));
        try (FakeSupervise reinstalled = new FakeSupervise(service(), 7).start()) {
            supervise.send(service(), 'u');

            assertThat(reinstalled.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('u');
            assertThat(supervise.status(service()).getPid()).isEqualTo(7);
        }
    }

    @Test
    public void stuck_opens_are_limited_and_a_rollback_has_a_reserve() throws Exception {
        int       base    = Supervise.workers(); // threads stuck from other tests count too
        Supervise limited = new Supervise(Duration.ofMillis(100), base + 2, 1);
        List<FakeSupervise> services = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) { // recreated fifos, nobody reading: each leaves a stuck open
                services.add(new FakeSupervise(tmp.getRoot().toPath().resolve("svc-" + i), 42));
            }
            for (int i = 0; i < 2; i++) {
                Path dir = services.get(i).dir();
                assertThatThrownBy(() -> limited.send(dir, 'd')).hasMessageContaining("nothing reads control");
            }
            assertThat(Supervise.workers()).isEqualTo(base + 2);

            Path third = services.get(2).dir();
            assertThatThrownBy(() -> limited.send(third, 'd')).hasMessageContaining("too many supervise operations");
            assertThatThrownBy(() -> limited.status(third)).hasMessageContaining("too many supervise operations");
            // the rollback may use the reserve
            assertThatThrownBy(() -> limited.sendUninterruptibly(third, 'u')).hasMessageContaining("nothing reads control");
            assertThat(Supervise.workers()).isEqualTo(base + 3);
        } finally {
            for (FakeSupervise service : services) { // readers release the stuck opens
                service.start();
                service.close();
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Supervise.workers() > base && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(Supervise.workers()).isEqualTo(base);
    }

    @Test
    public void a_thread_that_cannot_be_started_leaves_nothing_registered() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        int base = Supervise.workers();
        Supervise flaky = new Supervise(Duration.ofMillis(500), Supervise.WORKER_LIMIT, Supervise.WORKER_RESERVE, r -> {
            if (fail.getAndSet(false)) {
                throw new OutOfMemoryError("unable to create native thread");
            }
            return new Thread(r);
        });
        try (FakeSupervise fake = new FakeSupervise(service(), 42).start()) {
            assertThatThrownBy(() -> flaky.send(service(), 'd'))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cannot start the I/O thread");
            assertThat(Supervise.workers()).isEqualTo(base);

            flaky.send(service(), 'u'); // the fifo is free again: no waiting on a ghost

            assertThat(fake.commands().poll(5, TimeUnit.SECONDS)).isEqualTo('u');
        }
    }

    @Test
    public void a_socket_in_place_of_the_fifo_is_refused() throws Exception {
        Path dir = Files.createTempDirectory(Path.of("/tmp"), "sv"); // a unix socket path must be short
        try (java.nio.channels.ServerSocketChannel socket = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            Files.createDirectories(dir.resolve("supervise"));
            socket.bind(java.net.UnixDomainSocketAddress.of(dir.resolve("supervise/control")));

            assertThatThrownBy(() -> supervise.send(dir, 'd'))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("is not a fifo");
        } finally {
            Files.deleteIfExists(dir.resolve("supervise/control"));
            Files.deleteIfExists(dir.resolve("supervise"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void without_supervise_there_is_no_status() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42)) {
            assertThatThrownBy(() -> supervise.status(service()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("supervise is not running");
        }
    }

    @Test
    public void a_regular_file_in_place_of_the_fifo_is_not_written() throws Exception {
        Files.createDirectories(service().resolve("supervise"));
        Path control = Files.createFile(service().resolve("supervise/control"));

        assertThatThrownBy(() -> supervise.send(service(), 'd'))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is not a fifo");
        assertThat(control).isEmptyFile();
    }

    @Test
    public void a_link_in_place_of_the_fifo_is_not_followed() throws Exception {
        try (FakeSupervise other = new FakeSupervise(tmp.getRoot().toPath().resolve("other"), 1).start()) {
            Files.createDirectories(service().resolve("supervise"));
            Files.createSymbolicLink(service().resolve("supervise/control"), other.dir().resolve("supervise/control"));

            assertThatThrownBy(() -> supervise.send(service(), 'd')).isInstanceOf(IOException.class);
            assertThat(other.commands().poll(500, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    public void a_control_fifo_without_write_permission_is_refused_at_once() throws Exception {
        assumeFalse("root writes anyway", "root".equals(System.getProperty("user.name")));
        try (FakeSupervise fake = new FakeSupervise(service(), 42).start()) {
            Files.setPosixFilePermissions(service().resolve("supervise/control"), PosixFilePermissions.fromString("r--------"));

            assertThatThrownBy(() -> supervise.send(service(), 'd')).isInstanceOf(AccessDeniedException.class);
            assertThat(fake.commands()).isEmpty();
        }
    }

    @Test
    public void a_status_of_another_length_is_refused() throws Exception {
        try (FakeSupervise fake = new FakeSupervise(service(), 42).start()) {
            Files.write(service().resolve("supervise/status"), new byte[20]);

            assertThatThrownBy(() -> supervise.status(service()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("not 18");
        }
    }

    @Test
    public void an_unknown_command_is_refused() {
        assertThatThrownBy(() -> supervise.send(service(), 'z')).isInstanceOf(IllegalArgumentException.class);
    }
}
