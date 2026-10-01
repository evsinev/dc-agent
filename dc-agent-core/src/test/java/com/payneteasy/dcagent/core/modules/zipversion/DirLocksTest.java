package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DirLocksTest {

    private static final Duration SHORT = Duration.ofMillis(400);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void a_second_call_waits_and_gets_503_past_the_deadline_then_proceeds() throws Exception {
        Path     dir   = tmp.newFolder("bundles").toPath().toRealPath();
        DirLocks locks = new DirLocks(SHORT);

        try (DirLocks.Held ignored = locks.acquire(dir)) {
            long start = System.nanoTime();
            Throwable error = CompletableFuture.supplyAsync(() -> attempt(locks, dir)).get(5, TimeUnit.SECONDS);
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(error).isInstanceOfSatisfying(ProblemException.class, e -> {
                assertThat(e.getHttpCode()).isEqualTo(503);
                assertThat(e.getMessage()).startsWith("busy");
            });
            assertThat(waited).isBetween(SHORT.toMillis() - 50, SHORT.toMillis() + 2000);
        }
        // the failed attempt left nothing taken: another thread gets the lock at once
        assertThat(CompletableFuture.supplyAsync(() -> attempt(locks, dir)).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    public void a_waiting_call_gets_the_lock_when_it_is_released() throws Exception {
        Path     dir   = tmp.newFolder("bundles").toPath().toRealPath();
        DirLocks locks = new DirLocks(Duration.ofSeconds(10));

        DirLocks.Held held = locks.acquire(dir);
        CompletableFuture<Throwable> waiting = CompletableFuture.supplyAsync(() -> attempt(locks, dir));
        Thread.sleep(200);
        assertThat(waiting).isNotDone();
        held.close();

        assertThat(waiting.get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    public void other_directories_do_not_wait() throws Exception {
        DirLocks locks = new DirLocks(SHORT);
        Path     one   = tmp.newFolder("one").toPath().toRealPath();
        Path     two   = tmp.newFolder("two").toPath().toRealPath();

        try (DirLocks.Held ignored = locks.acquire(one)) {
            assertThat(CompletableFuture.supplyAsync(() -> attempt(locks, two)).get(5, TimeUnit.SECONDS)).isNull();
        }
    }

    @Test
    public void a_failed_open_of_the_lock_file_releases_the_in_process_lock() throws Exception {
        Path     dir   = tmp.getRoot().toPath().toRealPath().resolve("not-yet");
        DirLocks locks = new DirLocks(SHORT);

        assertThatThrownBy(() -> locks.acquire(dir)).isInstanceOf(IOException.class);

        tmp.newFolder("not-yet");
        assertThat(CompletableFuture.supplyAsync(() -> attempt(locks, dir)).get(5, TimeUnit.SECONDS)).isNull();
    }

    /** Without FileChannel.tryLock the in-process lock alone would let this through. */
    @Test
    public void another_process_holding_the_lock_file_makes_the_call_wait() throws Exception {
        Path dir = tmp.newFolder("bundles").toPath().toRealPath();
        Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LockHolderMain.class.getName(), dir.toString())
                .redirectErrorStream(true)
                .start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream()));
            assertThat(out.readLine()).isEqualTo("locked");

            DirLocks locks = new DirLocks(SHORT);
            assertThat(attempt(locks, dir)).isInstanceOfSatisfying(ProblemException.class,
                    e -> assertThat(e.getHttpCode()).isEqualTo(503));

            holder.getOutputStream().close();
            assertThat(holder.waitFor(10, TimeUnit.SECONDS)).isTrue();
            // from another thread: the ReentrantLock is reentrant, the same thread would pass even if
            // the timeout path had left it held
            assertThat(CompletableFuture.supplyAsync(() -> attempt(new DirLocks(Duration.ofSeconds(5)), dir))
                    .get(10, TimeUnit.SECONDS)).isNull();
        } finally {
            holder.destroyForcibly();
        }
    }

    /** null when the lock was taken (and released), else the error. */
    private static Throwable attempt(DirLocks aLocks, Path aDir) {
        try (DirLocks.Held ignored = aLocks.acquire(aDir)) {
            return null;
        } catch (Throwable e) {
            return e;
        }
    }
}
