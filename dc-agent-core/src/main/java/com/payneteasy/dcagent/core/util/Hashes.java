package com.payneteasy.dcagent.core.util;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static java.nio.file.Files.readAllBytes;

public class Hashes {

    public static byte[] sha256(File aFile) {
        try {
            return sha256(readAllBytes(aFile.toPath()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file " + aFile.getAbsolutePath(), e);
        }
    }

    public static byte[] sha256(byte[] aBytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    aBytes
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-256 alg", e);
        }
    }

}
