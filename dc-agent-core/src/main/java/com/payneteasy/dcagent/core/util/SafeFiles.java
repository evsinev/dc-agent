package com.payneteasy.dcagent.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.payneteasy.dcagent.core.exception.DcProblem.DIR_NOT_FOUND;
import static com.payneteasy.dcagent.core.exception.DcProblem.FILE_NOT_FOUND;
import static com.payneteasy.dcagent.core.exception.HttpProblemBuilder.problem;

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

    public static File createDirs(File aDir) {
        if(aDir.exists()) {
            return aDir;
        }
        LOG.debug("Creating dir {} ...", aDir.getAbsolutePath());
        if(!aDir.mkdirs()) {
            throw new IllegalStateException("Cannot create dir " + aDir);
        }
        return aDir;
    }

    public static void writeFile(File aFile, InputStream in) {
        byte[] buf = new byte[4096];
        try {
            try(FileOutputStream out = new FileOutputStream(aFile)) {
                int count;
                while ( ( count = in.read(buf)) >= 0) {
                    out.write(buf, 0, count);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write file " + aFile.getAbsolutePath(), e);
        }
    }

    public static File ensureFileExists(File aFile) {
        if (aFile.isDirectory()) {
            throw problem(FILE_NOT_FOUND)
                    .detail(String.format("%s is a directory not a file", aFile.getName()))
                    .memo(String.format("%s is a directory not a file", aFile.getAbsolutePath()))
                    .exception();
        }

        if (!aFile.exists()) {
            throw problem(FILE_NOT_FOUND)
                    .detail(String.format("File %s does not exist", aFile.getName()))
                    .memo(String.format("File %s does not exist", aFile.getAbsolutePath()))
                    .exception();
        }

        if (!aFile.canRead()) {
            throw problem(FILE_NOT_FOUND)
                    .detail(String.format("File %s cannot be read", aFile.getName()))
                    .memo(String.format("File %s cannot be read", aFile.getAbsolutePath()))
                    .exception();
        }

        return aFile;
    }

    public static File ensureDirExists(File aFile) {
        if (aFile.isFile()) {
            throw problem(DIR_NOT_FOUND)
                    .detail(String.format("%s is a file not a directory", aFile.getName()))
                    .memo(String.format("%s is a file not a directory", aFile.getAbsolutePath()))
                    .exception();
        }

        if (!aFile.exists()) {
            throw problem(DIR_NOT_FOUND)
                    .detail(String.format("Directory %s does not exist", aFile.getName()))
                    .memo(String.format("Directory %s does not exist", aFile.getAbsolutePath()))
                    .exception();
        }

        return aFile;
    }
}
