package com.payneteasy.dcagent.core.modules.zipachive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;

public class TempFile implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger( TempFile.class );

    private final File file;

    public TempFile(String aName, String aExtension) throws IOException {
        file = File.createTempFile(aName + "-" + System.currentTimeMillis(), "." + aExtension);
        LOG.debug("Created temp file {}", file.getAbsolutePath());
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
        if(file.exists()) {
            Files.delete(file.toPath());
        }
    }

    public File getFile() {
        return file;
    }
}
