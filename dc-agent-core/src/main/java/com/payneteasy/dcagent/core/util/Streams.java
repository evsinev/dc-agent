package com.payneteasy.dcagent.core.util;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class Streams {

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int    count;
        while ((count = in.read(buf)) >= 0) {
            out.write(buf, 0, count);
        }
        out.flush();
    }

    public static void writeFile(File aFile, InputStream aInputStream) throws IOException {
        try (FileOutputStream out = new FileOutputStream(aFile)) {
            byte[] buf = new byte[4096];
            int    count;
            while ((count = aInputStream.read(buf)) >= 0) {
                out.write(buf, 0, count);
            }
        }
    }

    public static File writeToTempFile(InputStream aInputStream, String aPrefix, String aSuffix) {
        File tempFile;
        try {
            Path tempPath;
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                tempPath = Files.createTempFile(
                        aPrefix,
                        aSuffix,
                        PosixFilePermissions.asFileAttribute(
                                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                        )
                );
            } else {
                tempPath = Files.createTempFile(aPrefix, aSuffix);
            }
            tempFile = tempPath.toFile();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create temp file", e);
        }

        try {
            writeFile(tempFile, aInputStream);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write to temp file " + tempFile.getAbsolutePath(), e);
        }
        return tempFile;
    }

    public static byte[] readAllBytes(InputStream aInputStream) {
        return combineBuffers(
                readBuffers(
                        aInputStream
                )
        );
    }

    private static byte[] combineBuffers(List<byte[]> buffers) {
        int size = buffers.stream().map(it -> it.length).reduce(0, Integer::sum);

        byte[] all = new byte[size];
        int position = 0;
        for (byte[] buffer : buffers) {
            System.arraycopy(buffer, 0, all, position, buffer.length);
            position += buffer.length;
        }

        return all;
    }

    private static List<byte[]> readBuffers(InputStream aInputStream) {
        List<byte[]> buffers = new ArrayList<>();
        byte[]       buf     = new byte[4096];
        int          count;

        try {
            while ( (count = aInputStream.read(buf)) != -1) {
                byte[] result = new byte[count];
                System.arraycopy(buf, 0, result, 0, count);
                buffers.add(result);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read from input stream", e);
        }

        return buffers;
    }

}
