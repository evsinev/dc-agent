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
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per {@code dir}, held for a whole {@code zip-archive-version} call: first an in-process
 * lock keyed by the real path, then {@code FileChannel.tryLock()} on {@code <dir>/.dc-agent.lock}
 * (another agent process, e.g. during a restart). Both are needed: a JVM throws
 * {@link OverlappingFileLockException} for a second lock on one file instead of waiting — the
 * in-process lock is JVM-wide (static) so that cannot happen. Waiting for both is bounded by one
 * deadline; past it the call is 503 {@code busy}, with nothing held.
 */
public final class DirLocks {

    static final String LOCK_FILE = ".dc-agent.lock";

    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();
    private static final long                                   POLL_MILLIS = 50;

    private final Duration wait;

    public DirLocks(Duration aWait) {
        wait = aWait;
    }

    /** A held lock of one directory; {@link #close} releases the file lock, then the in-process one. */
    public static final class Held implements AutoCloseable {

        private final ReentrantLock inProcess;
        private final FileChannel   channel;
        private final FileLock      fileLock;

        private Held(ReentrantLock aInProcess, FileChannel aChannel, FileLock aFileLock) {
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
                inProcess.unlock();
            }
        }
    }

    /** @param aRealDir {@code dir} after {@code toRealPath()}: one key for every spelling of it */
    public Held acquire(Path aRealDir) throws IOException {
        long          deadline  = System.nanoTime() + wait.toNanos();
        ReentrantLock inProcess = IN_PROCESS.computeIfAbsent(aRealDir, key -> new ReentrantLock(true));
        try {
            if (!inProcess.tryLock(remaining(deadline), TimeUnit.NANOSECONDS)) {
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
                    inProcess.unlock();
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
