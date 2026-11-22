package com.payneteasy.dcagent.servlets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.IntUnaryOperator;

/**
 * A service directory with real fifos ({@code mkfifo}) and a supervise stand-in: while started it
 * keeps {@code ok} open and records every byte written to {@code control}; {@code onCommand} maps a
 * command to the pid it leaves in {@code status} (the current pid to keep it).
 */
public final class FakeSupervise implements AutoCloseable {

    private final Path                     dir;
    private final BlockingQueue<Character> commands = new LinkedBlockingQueue<>();
    private volatile long                  pid;
    private volatile IntUnaryOperator      onCommand = aCommand -> -1; // -1: keep the pid
    private volatile long                  reactionDelayMillis;
    private FileChannel                    ok;
    private FileChannel                    control;
    private FileChannel                    filling; // its own channel: a FileChannel serializes read and write
    private Thread                         reader;

    public FakeSupervise(Path aServiceDir, long aPid) throws IOException, InterruptedException {
        dir = aServiceDir;
        Files.createDirectories(dir.resolve("supervise"));
        mkfifo(dir.resolve("supervise/control"));
        mkfifo(dir.resolve("supervise/ok"));
        setPid(aPid);
    }

    public Path dir() {
        return dir;
    }

    /** What a command does to the pid ({@code -1}: unchanged), after {@code aDelayMillis}. */
    public FakeSupervise onCommand(IntUnaryOperator aOnCommand, long aDelayMillis) {
        onCommand           = aOnCommand;
        reactionDelayMillis = aDelayMillis;
        return this;
    }

    /** Opens the fifos read+write (never blocks, never sees EOF) and reads the commands. */
    public FakeSupervise start() throws IOException {
        ok      = FileChannel.open(dir.resolve("supervise/ok"), StandardOpenOption.READ, StandardOpenOption.WRITE);
        control = FileChannel.open(dir.resolve("supervise/control"), StandardOpenOption.READ, StandardOpenOption.WRITE);
        reader  = new Thread(() -> {
            ByteBuffer one = ByteBuffer.allocate(1);
            try {
                while (control.read(one.clear()) == 1) {
                    char command = (char) one.get(0);
                    commands.add(command);
                    int next = onCommand.applyAsInt(command);
                    if (next >= 0) {
                        Thread.sleep(reactionDelayMillis);
                        setPid(next);
                    }
                }
            } catch (Exception e) {
                // closed
            }
        }, "fake-supervise");
        reader.setDaemon(true);
        reader.start();
        return this;
    }

    /**
     * Opens the fifos like {@link #start()} but does not read control (a hung supervise), and fills
     * it with zero bytes until writes block. {@link #resume()} reads again.
     */
    public FakeSupervise startStuck() throws IOException, InterruptedException {
        ok      = FileChannel.open(dir.resolve("supervise/ok"), StandardOpenOption.READ, StandardOpenOption.WRITE);
        control = FileChannel.open(dir.resolve("supervise/control"), StandardOpenOption.READ, StandardOpenOption.WRITE);
        filling = FileChannel.open(dir.resolve("supervise/control"), StandardOpenOption.WRITE);
        Thread filler = new Thread(() -> {
            try {
                for (int i = 0; i < 256; i++) { // 1 MiB: more than any pipe holds; ends once read again
                    filling.write(ByteBuffer.wrap(new byte[4096]));
                }
            } catch (Exception e) {
                // closed
            }
        }, "fake-supervise-filler");
        filler.setDaemon(true);
        filler.start();
        Thread.sleep(300); // the pipe is full, the filler blocked
        return this;
    }

    /** Reads control again (after {@link #startStuck()}): records the commands, skips the filler's zeros. */
    public FakeSupervise resume() {
        reader = new Thread(() -> {
            ByteBuffer chunk = ByteBuffer.allocate(4096);
            try {
                while (control.read(chunk.clear()) > 0) {
                    for (int i = 0; i < chunk.position(); i++) {
                        if (chunk.get(i) != 0) {
                            commands.add((char) chunk.get(i));
                        }
                    }
                }
            } catch (Exception e) {
                // closed
            }
        }, "fake-supervise-resumed");
        reader.setDaemon(true);
        reader.start();
        return this;
    }

    public BlockingQueue<Character> commands() {
        return commands;
    }

    public void setPid(long aPid) throws IOException {
        pid = aPid;
        writeStatus(aPid, System.currentTimeMillis());
    }

    /** The status file as supervise writes it; {@code aWhenMillis} — when the state changed. */
    public void writeStatus(long aPid, long aWhenMillis) throws IOException {
        ByteBuffer buffer  = ByteBuffer.allocate(18);
        long       seconds = Math.floorDiv(aWhenMillis, 1000L);
        buffer.putLong((1L << 62) + 10 + seconds);
        buffer.putInt((int) (Math.floorMod(aWhenMillis, 1000L) * 1_000_000L));
        buffer.put((byte) aPid).put((byte) (aPid >> 8)).put((byte) (aPid >> 16)).put((byte) (aPid >> 24));
        buffer.put((byte) 0).put((byte) 'u');
        Path tmp = dir.resolve("supervise/status.new");
        Files.write(tmp, buffer.array());
        Files.move(tmp, dir.resolve("supervise/status"), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public long pid() {
        return pid;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.interrupt();
        }
        if (filling != null) {
            filling.close();
        }
        if (control != null) {
            control.close();
        }
        if (ok != null) {
            ok.close();
        }
    }

    public static void mkfifo(Path aPath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("mkfifo", aPath.toString()).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IOException("mkfifo " + aPath + " failed");
        }
    }
}
