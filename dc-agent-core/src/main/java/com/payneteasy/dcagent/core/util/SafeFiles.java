package com.payneteasy.dcagent.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SafeFiles {

    private static final Logger LOG = LoggerFactory.getLogger(SafeFiles.class);

    public static List<File> listFiles(File aDir, FileFilter aFilter) {
        File[] files = aDir.listFiles(aFilter);
        if (files == null) {
            return Collections.emptyList();
        }

        return Arrays.asList(files);
    }

    public static void deleteFileWithWarning(File aFile, String aPurpose) {
        if (aFile.delete()) {
            return;
        }

        LOG.warn("Cannot delete file {} for {}", aFile.getAbsoluteFile(), aPurpose);
    }

    public static String readFile(File aFile) {
        try {
            try(LineNumberReader in = new LineNumberReader(new InputStreamReader(new FileInputStream(aFile), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder sb = new StringBuilder();
                while( (line = in.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file " + aFile.getAbsolutePath(), e);
        }
    }

    public static void writeFile(File aSource, byte[] body) {
        createDirs(aSource.getParentFile());

        try {
            try(FileOutputStream out = new FileOutputStream(aSource)) {
                out.write(body);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write file " + aSource, e);
        }
    }

    public static void createDirs(File aDir) {
        if(aDir.exists()) {
            return;
        }
        LOG.debug("Creating dir {} ...", aDir.getAbsolutePath());
        if(!aDir.mkdirs()) {
            throw new IllegalStateException("Cannot create dir " + aDir);
        }
    }
}
