package com.payneteasy.dcagent.modules.zipachive;

import java.io.*;
import java.nio.file.Files;

public class TempFile implements Closeable {

    private final File file;

    public TempFile(String aName, String aExtension) throws IOException {
        file = File.createTempFile(aName + "-" + System.currentTimeMillis(), "." + aExtension);
    }

    public void writeFromInputStream(InputStream aInputStream) throws IOException {
        byte[] buf = new byte[1024 * 4];
        try (FileOutputStream out = new FileOutputStream(file)) {

            int count;
            while( (count = aInputStream.read(buf)) != -1) {
                out.write(buf, 0, count);
            }
        }
    }

    @Override
    public void close() throws IOException {
        Files.delete(file.toPath());
    }

    public File getFile() {
        return file;
    }
}
