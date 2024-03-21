package com.payneteasy.dcagent.core.util;

import java.io.*;
import java.util.ArrayList;
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
            tempFile = File.createTempFile(aPrefix, aSuffix);
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
