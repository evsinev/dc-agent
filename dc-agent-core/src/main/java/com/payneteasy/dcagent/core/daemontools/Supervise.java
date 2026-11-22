package com.payneteasy.dcagent.core.daemontools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What daemontools' {@code svc} and {@code svstat} do, without running them: a command is one byte
 * written to the fifo {@code supervise/control}; the status is the 18 bytes of
 * {@code supervise/status}, valid only while supervise reads the fifo {@code supervise/ok}.
 * <p>
 * So the agent needs no exec, and a refusal (permissions, Landlock, no supervise) is an exception —
 * {@code svc} only warns and still exits with 0.
 * <p>
 * Java has no O_NONBLOCK: opening a fifo without a reader, or writing to a full one, blocks. Each
 * open (and write) runs on its own daemon thread, bounded by {@code timeout}; the calling thread only
 * waits for it, so interrupting the caller never interrupts the I/O halfway. There is at most one
 * such thread per fifo, registered before it starts: a second call for the same fifo waits for the
 * first one. If that one is stuck before the fifo opened (no supervise), the call is refused; if it
 * is stuck writing (supervise has the fifo but does not read it), the command is queued behind it,
 * written by the same thread right after — so a {@code u} sent after a stuck {@code d} reaches
 * supervise after it, whenever supervise reads again. The queue keeps the order of the commands —
 * like the pipe itself, a little longer — and holds up to {@value #QUEUE_LIMIT}; beyond that a
 * command is refused, except {@code u}: a start (a deploy's rollback) always finds room, and a
 * {@code u} right behind another one is the same request, so it is not added again (at most
 * {@value #QUEUE_LIMIT} + 1 commands). The outcome is always known:
 * <ul>
 *     <li>given up on before the fifo opened — the thread closes it without writing once a reader
 *     appears: the command is never delivered late ({@link IOException});</li>
 *     <li>written — delivered, even when the caller was interrupted meanwhile (the interrupt is kept);</li>
 *     <li>opened, but the write did not finish in time — it may still be delivered
 *     ({@link CommandMayBeDelivered}).</li>
 * </ul>
 * Like {@code svc}, the path is resolved through links up to the fifo ({@code supervise} itself may
 * be a link, e.g. to {@code /run}); the fifo is not followed and must be a fifo. Replacing it between
 * the check and the open needs write access to the supervise directory — control of the service.
 */
public class Supervise {

    /** The fifo opened but the command was not written in time: supervise may still act on it. */
    public static class CommandMayBeDelivered extends IOException {
        public CommandMayBeDelivered(String aMessage) {
            super(aMessage);
        }
    }

    private static final int STATUS_LENGTH = 18;
    private static final int S_IFMT        = 0170000;
    private static final int S_IFIFO       = 0010000;

    private static final int QUEUE_LIMIT = 16;

    private static final int OPENING   = 0;
    private static final int OPENED    = 1;
    private static final int ABANDONED = 2;

    /**
     * The I/O thread of each fifo, from before it starts until it ends: at most one per fifo. Keyed
     * by the fifo itself (device and inode): a fifo recreated under the same path — a reinstalled
     * service — is another one, not blocked by a thread stuck on the old one.
     */
    private static final ConcurrentMap<Object, Attempt> ATTEMPTS = new ConcurrentHashMap<>();

    /**
     * I/O threads alive in this process. A thread stuck opening a fifo that nobody ever reads (and
     * that is then deleted) cannot be woken: the limit keeps such threads from piling up — beyond it
     * an operation is refused at once. Rollbacks ({@link #sendUninterruptibly}) have a reserve.
     */
    private static final AtomicInteger WORKERS = new AtomicInteger();

    public static final int WORKER_LIMIT   = 64;
    public static final int WORKER_RESERVE = 16;

    private final Duration                             timeout;
    private final int                                  workerLimit;
    private final int                                  workerReserve;
    private final java.util.concurrent.ThreadFactory   threads;

    public Supervise(Duration aTimeout) {
        this(aTimeout, WORKER_LIMIT, WORKER_RESERVE, Thread::new);
    }

    Supervise(Duration aTimeout, int aWorkerLimit, int aWorkerReserve) {
        this(aTimeout, aWorkerLimit, aWorkerReserve, Thread::new);
    }

    Supervise(Duration aTimeout, int aWorkerLimit, int aWorkerReserve, java.util.concurrent.ThreadFactory aThreads) {
        timeout       = aTimeout;
        workerLimit   = aWorkerLimit;
        workerReserve = aWorkerReserve;
        threads       = aThreads;
    }

    /** I/O threads alive now (tests). */
    static int workers() {
        return WORKERS.get();
    }

    /** Sends one command, as {@code svc -<command>}: u, d, o, p, c, h, a, i, t, k, x. */
    public void send(Path aServiceDir, char aCommand) throws IOException, InterruptedException {
        if ("udopchaitkx".indexOf(aCommand) < 0) {
            throw new IllegalArgumentException("Unknown supervise command '" + aCommand + "'");
        }
        run(aServiceDir.resolve("supervise/control"), (byte) aCommand, false);
    }

    /**
     * {@link #send}, carried through an interrupt: the outcome is decided by the timeout alone, the
     * interrupt is kept for the caller. For a command that must not be lost — a deploy's rollback.
     */
    public void sendUninterruptibly(Path aServiceDir, char aCommand) throws IOException {
        if ("udopchaitkx".indexOf(aCommand) < 0) {
            throw new IllegalArgumentException("Unknown supervise command '" + aCommand + "'");
        }
        try {
            run(aServiceDir.resolve("supervise/control"), (byte) aCommand, true);
        } catch (InterruptedException e) {
            throw new IllegalStateException("not interrupted when uninterruptible", e); // never happens
        }
    }

    /** The status, as {@code svstat} reads it: refused while supervise is not running. */
    public SuperviseStatusFile status(Path aServiceDir) throws IOException, InterruptedException {
        run(aServiceDir.resolve("supervise/ok"), null, false);
        Path   file = aServiceDir.resolve("supervise/status");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(STATUS_LENGTH + 1);
        }
        if (bytes.length != STATUS_LENGTH) {
            throw new IOException(file + " has " + bytes.length + " bytes, not " + STATUS_LENGTH
                    + " (daemontools' supervise; runit, daemontools-encore and s6 write other formats)");
        }
        return new SuperviseStatusFileParser().parseStatusFile(bytes);
    }

    /** One I/O on a fifo: open for writing, write {@code aByte} if given, then the queued one, close. */
    private static final class Attempt {
        final AtomicInteger             state  = new AtomicInteger(OPENING);
        final CompletableFuture<Void>   done   = new CompletableFuture<>();
        /** Completed once the thread has left the fifo (unregistered): the next call may start. */
        final CompletableFuture<Void>   ended  = new CompletableFuture<>();
        /** Commands queued while this attempt was stuck writing; guarded by the attempt. */
        final java.util.ArrayDeque<Byte> queued = new java.util.ArrayDeque<>();
        /** Set by the I/O thread once it writes no more: a later command is sent on its own. */
        boolean closed;

        synchronized boolean queue(byte aCommand) {
            if (closed) {
                return false;
            }
            if (aCommand == 'u') { // always room for a start; one right behind another is the same
                if (!Byte.valueOf((byte) 'u').equals(queued.peekLast())) {
                    queued.add(aCommand);
                }
                return true;
            }
            if (queued.size() >= QUEUE_LIMIT) {
                return false;
            }
            queued.add(aCommand);
            return true;
        }

        /** The next queued command, or null — and then no more are taken. */
        synchronized Byte nextQueued() {
            Byte next = queued.poll();
            if (next == null) {
                closed = true;
            }
            return next;
        }

        synchronized void close() {
            closed = true;
        }
    }

    private void run(Path aFifo, Byte aByte, boolean aUninterruptible) throws IOException, InterruptedException {
        if (!isFifo(aFifo)) { // never a regular file, a device, a socket or a link in its place
            throw new IOException(aFifo + " is not a fifo");
        }
        // the service itself, whatever link names it (/service/app -> /etc/service.d/app)
        Path    path        = aFifo.getParent().toRealPath().resolve(aFifo.getFileName());
        Object  fileKey     = Files.readAttributes(aFifo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        Object  key         = fileKey != null ? fileKey : path;
        long    deadline    = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;

        Attempt attempt = new Attempt();
        while (true) { // wait for the I/O thread of this fifo, if any
            Attempt previous = ATTEMPTS.putIfAbsent(key, attempt);
            if (previous == null) {
                break;
            }
            long left = deadline - System.nanoTime();
            try {
                previous.ended.get(Math.max(left, 0), TimeUnit.NANOSECONDS); // no dependants left behind
            } catch (TimeoutException e) {
                restoreInterrupt(interrupted);
                if (aByte != null && previous.state.get() == OPENED && previous.queue(aByte)) {
                    throw new CommandMayBeDelivered("supervise for " + service(aFifo) + " does not read "
                            + aFifo.getFileName() + "; the command is queued behind the earlier one");
                }
                throw new IOException("supervise is not running for " + service(aFifo) + ": an earlier "
                        + aFifo.getFileName() + " request is still waiting for it");
            } catch (InterruptedException e) {
                if (aUninterruptible) {
                    interrupted = true;
                    continue; // the same deadline
                }
                // behind a stuck write the command is queued anyway (a rollback must not be lost to an
                // interrupt); the interrupt is kept
                if (aByte != null && previous.state.get() == OPENED && previous.queue(aByte)) {
                    Thread.currentThread().interrupt();
                    throw new CommandMayBeDelivered("supervise for " + service(aFifo) + " has not read "
                            + aFifo.getFileName() + " yet; the command is queued behind the earlier one");
                }
                throw e; // nothing started, nothing queued
            } catch (ExecutionException e) {
                // ended: try again
            }
        }

        int limit = aUninterruptible ? workerLimit + workerReserve : workerLimit;
        if (WORKERS.incrementAndGet() > limit) {
            WORKERS.decrementAndGet();
            unregister(key, attempt);
            restoreInterrupt(interrupted);
            throw new IOException("too many supervise operations are waiting (" + limit + "), refused for " + service(aFifo));
        }
        try {
            Thread worker = threads.newThread(() -> io(aFifo, aByte, attempt, key));
            worker.setName("supervise " + path);
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable e) { // no thread: nothing registered may stay behind
            WORKERS.decrementAndGet();
            unregister(key, attempt);
            restoreInterrupt(interrupted);
            throw new IOException("cannot start the I/O thread for " + aFifo, e);
        }

        while (true) {
            try {
                attempt.done.get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
                restoreInterrupt(interrupted);
                return; // done: opened (and written)
            } catch (ExecutionException e) {
                restoreInterrupt(interrupted);
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (InterruptedException e) {
                interrupted = true;
                if (!aUninterruptible && attempt.state.compareAndSet(OPENING, ABANDONED)) {
                    throw e; // not opened: nothing will be written
                }
                // opened: the write is under way and its outcome is near — wait for it below
            } catch (TimeoutException e) {
                if (attempt.state.compareAndSet(OPENING, ABANDONED)) {
                    restoreInterrupt(interrupted);
                    throw new IOException("supervise is not running for " + service(aFifo) + ": nothing reads "
                            + aFifo.getFileName() + " within " + timeout.toMillis() + " ms");
                }
                if (attempt.done.isDone()) {
                    continue; // finished at the very moment
                }
                restoreInterrupt(interrupted);
                throw new CommandMayBeDelivered("supervise for " + service(aFifo) + " opened " + aFifo.getFileName()
                        + " but did not take the command within " + timeout.toMillis() + " ms; it may still act on it");
            }
        }
    }

    /** On the I/O thread: never interrupted, so a written byte is always reported as written. */
    private static void io(Path aFifo, Byte aByte, Attempt aAttempt, Object aKey) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(aFifo, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            if (!aAttempt.state.compareAndSet(OPENING, OPENED)) {
                return; // given up on: nothing is written
            }
            if (aByte != null) {
                write(channel, aFifo, aByte);
            }
            aAttempt.done.complete(null);
            // commands queued while this one was stuck go right after it, in order
            for (Byte next = aAttempt.nextQueued(); next != null; next = aAttempt.nextQueued()) {
                write(channel, aFifo, next);
            }
        } catch (Throwable e) {
            aAttempt.done.completeExceptionally(e);
        } finally {
            aAttempt.close(); // abandoned or failed: queued commands are not written (their senders heard "may")
            closeQuietly(channel);
            aAttempt.done.complete(null); // abandoned: nothing was written
            unregister(aKey, aAttempt);
            WORKERS.decrementAndGet();
        }
    }

    private static void unregister(Object aKey, Attempt aAttempt) {
        aAttempt.close();
        ATTEMPTS.remove(aKey, aAttempt);
        aAttempt.ended.complete(null);
    }

    private static void write(FileChannel aChannel, Path aFifo, byte aByte) throws IOException {
        if (aChannel.write(ByteBuffer.wrap(new byte[]{aByte})) != 1) {
            throw new IOException("Cannot write to " + aFifo);
        }
    }

    /** A fifo exactly ({@code S_IFIFO}); where the unix view is missing, "other" (not file, dir, link). */
    static boolean isFifo(Path aPath) throws IOException {
        try {
            Object mode = Files.getAttribute(aPath, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return mode instanceof Integer m && (m & S_IFMT) == S_IFIFO;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return Files.readAttributes(aPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        }
    }

    private static Path service(Path aFifo) {
        return aFifo.getParent().getParent();
    }

    private static void restoreInterrupt(boolean aInterrupted) {
        if (aInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(FileChannel aChannel) {
        if (aChannel == null) {
            return;
        }
        try {
            aChannel.close();
        } catch (IOException e) {
            // the outcome is decided by the write, not by close
        }
    }
}
