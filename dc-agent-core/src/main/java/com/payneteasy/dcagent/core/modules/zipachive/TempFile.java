package com.payneteasy.dcagent.core.modules.zipachive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;

import static com.payneteasy.dcagent.core.util.Strings.hasText;
import static java.lang.Boolean.parseBoolean;

public class TempFile implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger( TempFile.class );

    private static final String  DEBUG_KEEP_TEMP_FILE = System.getenv("DEBUG_KEEP_TEMP_FILE");
    private static final boolean KEEP_TEMP_FILE       = hasText(DEBUG_KEEP_TEMP_FILE) && parseBoolean(DEBUG_KEEP_TEMP_FILE);

    private final File file;

    public TempFile(String aName, String aExtension) {
        try {
            file = File.createTempFile(aName + "-" + System.currentTimeMillis(), "." + aExtension);
            LOG.debug("Created temp file {}", file.getAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create temp file", e);
        }
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
        if (KEEP_TEMP_FILE) {
            LOG.warn("Temp file did not delete {}", file.getAbsolutePath());
        }

        if(file.exists()) {
            Files.delete(file.toPath());
        }
    }

    public File getFile() {
        return file;
    }
}
