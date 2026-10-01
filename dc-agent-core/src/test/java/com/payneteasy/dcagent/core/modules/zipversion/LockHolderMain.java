package com.payneteasy.dcagent.core.modules.zipversion;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Another process holding {@code <dir>/.dc-agent.lock}: prints {@code locked}, holds until stdin closes. */
public final class LockHolderMain {

    private LockHolderMain() {
    }

    public static void main(String[] aArgs) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(aArgs[0]).resolve(DirLocks.LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() != -1) {
                // hold
            }
        }
    }
}
