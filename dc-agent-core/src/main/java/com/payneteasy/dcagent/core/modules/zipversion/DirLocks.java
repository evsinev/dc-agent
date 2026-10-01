package com.payneteasy.dcagent.core.modules.zipversion;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/**
 * One lock per {@code dir}, held for a whole {@code zip-archive-version} call: first an in-process
 * permit keyed by the real path, then {@code FileChannel.tryLock()} on {@code <dir>/.dc-agent.lock}
 * (another agent process, e.g. during a restart). Both are needed: a JVM throws
 * {@link OverlappingFileLockException} for a second lock on one file instead of waiting — the
 * in-process permit is JVM-wide (static) so that cannot happen. Waiting for both is bounded by one
 * deadline; past it the call is 503 {@code busy}, with nothing held.
 * <p>The permit is a {@code Semaphore(1)}, not a {@code ReentrantLock}: it is taken here and given
 * back by {@link Held#close} — a hand-over a lock owned by a thread does not express — and it is
 * not reentrant, so a nested acquire in one thread waits instead of passing silently.
 */
public final class DirLocks {

    static final String LOCK_FILE = ".dc-agent.lock";

    private static final ConcurrentHashMap<Path, Semaphore> IN_PROCESS  = new ConcurrentHashMap<>();
    private static final long                               POLL_MILLIS = 50;

    private final Duration wait;

    public DirLocks(Duration aWait) {
        wait = aWait;
    }

    /** A held lock of one directory; {@link #close} releases the file lock, then the in-process permit. */
    public static final class Held implements AutoCloseable {

        private final Semaphore   inProcess;
        private final FileChannel channel;
        private final FileLock    fileLock;

        private Held(Semaphore aInProcess, FileChannel aChannel, FileLock aFileLock) {
            inProcess = aInProcess;
            channel   = aChannel;
            fileLock  = aFileLock;
        }

        @Override
        public void close() throws IOException {
            try {
                try {
                    fileLock.release();
                } finally {
                    channel.close();
                }
            } finally {
                inProcess.release();
            }
        }
    }

    /** @param aRealDir {@code dir} after {@code toRealPath()}: one key for every spelling of it */
    public Held acquire(Path aRealDir) throws IOException {
        long          deadline  = System.nanoTime() + wait.toNanos();
        Semaphore inProcess = IN_PROCESS.computeIfAbsent(aRealDir, key -> new Semaphore(1, true));
        try {
            if (!inProcess.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }

        FileChannel channel    = null;
        boolean     handedOver = false;
        try {
            channel = FileChannel.open(aRealDir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            while (true) {
                FileLock fileLock;
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    // not expected behind the in-process lock; waiting is the safe answer
                    fileLock = null;
                }
                if (fileLock != null) {
                    Held held = new Held(inProcess, channel, fileLock);
                    handedOver = true;
                    return held;
                }
                long left = remaining(deadline);
                if (left <= 0) {
                    throw busy();
                }
                Thread.sleep(Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(left) + 1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        } finally {
            // every way out without a Held — busy, an open or lock error — leaves nothing taken
            if (!handedOver) {
                try {
                    if (channel != null) {
                        channel.close();
                    }
                } finally {
                    inProcess.release();
                }
            }
        }
    }

    private static long remaining(long aDeadline) {
        return Math.max(0, aDeadline - System.nanoTime());
    }

    private static RuntimeException busy() {
        return Problems.busy("another call is publishing into this directory");
    }
}
