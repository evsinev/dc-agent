package com.payneteasy.dcagent.core.util;

import java.io.*;

import static java.nio.file.Files.newInputStream;

public class FileCompare {

    public static boolean isFileIdentical(File aFile, byte[] aBytes) {
        if (!aFile.exists()) {
            return false;
        }

        if (aFile.length() != aBytes.length) {
            return false;
        }

        try {
            InputStream          leftStream  = newInputStream(aFile.toPath());
            ByteArrayInputStream rightStream = new ByteArrayInputStream(aBytes);
            return isStreamsIdentical(leftStream, rightStream);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + aFile.getAbsolutePath(), e);
        }

    }

    public static boolean isFileIdentical(File aLeft, File aRight) {
        if (!aLeft.exists()) {
            return false;
        }
        if (!aRight.exists()) {
            return false;
        }

        if (aLeft.length() != aRight.length()) {
            return false;
        }

        try {
            InputStream leftStream  = newInputStream(aLeft.toPath());
            InputStream rightStream = newInputStream(aRight.toPath());

            return isStreamsIdentical(leftStream, rightStream);

        } catch (IOException e) {
            throw new IllegalStateException("Cannot read files " + aLeft.getAbsolutePath() + " and " + aRight.getAbsolutePath());
        }

    }

    private static boolean isStreamsIdentical(InputStream leftStream, InputStream rightStream) throws IOException {
        try (BufferedInputStream leftIn = new BufferedInputStream(leftStream);
             BufferedInputStream rightIn = new BufferedInputStream(rightStream)) {
            int value;
            while ((value = leftIn.read()) >= 0) {
                if (value != rightIn.read()) {
                    return false;
                }
            }
        }
        return true;
    }
}
