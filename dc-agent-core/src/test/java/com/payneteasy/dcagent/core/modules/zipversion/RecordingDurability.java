package com.payneteasy.dcagent.core.modules.zipversion;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real fsyncs, each mark recorded in order; {@link #failOn} makes the next matching sync throw instead. */
final class RecordingDurability implements Durability {

    private final List<String> marks = new ArrayList<>();
    private String failMark;
    private int    failures;

    synchronized RecordingDurability failOn(String aMark, int aTimes) {
        failMark = aMark;
        failures = aTimes;
        return this;
    }

    synchronized List<String> marks() {
        return new ArrayList<>(marks);
    }

    synchronized void clear() {
        marks.clear();
    }

    @Override
    public void syncFile(FileChannel aChannel, String aMark) throws IOException {
        record(aMark);
        aChannel.force(true);
    }

    @Override
    public void syncDir(Path aDir, String aMark) throws IOException {
        record(aMark);
        FS.syncDir(aDir, aMark);
    }

    private synchronized void record(String aMark) throws IOException {
        marks.add(aMark);
        if (aMark.equals(failMark) && failures > 0) {
            failures--;
            throw new IOException("injected fsync failure at " + aMark);
        }
    }
}
