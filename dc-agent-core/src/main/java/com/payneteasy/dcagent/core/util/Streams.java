package com.payneteasy.dcagent.core.util;

import java.io.*;

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


}
