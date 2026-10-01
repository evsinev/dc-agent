package com.payneteasy.dcagent.core.modules.zipversion;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The {@code fsync} barriers of {@code zip-archive-version}, each named by a mark so a test can fail
 * exactly one of them and check their order. A failure is reported as {@link DurabilityException}
 * (500 {@code durability not confirmed}) by the callers.
 */
public interface Durability {

    void syncFile(FileChannel aChannel, String aMark) throws IOException;

    void syncDir(Path aDir, String aMark) throws IOException;

    /** {@code force(true)} on the file, and on the directory opened for reading (Linux, macOS). */
    Durability FS = new Durability() {
        @Override
        public void syncFile(FileChannel aChannel, String aMark) throws IOException {
            aChannel.force(true);
        }

        @Override
        public void syncDir(Path aDir, String aMark) throws IOException {
            try (FileChannel channel = FileChannel.open(aDir, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    };
}
