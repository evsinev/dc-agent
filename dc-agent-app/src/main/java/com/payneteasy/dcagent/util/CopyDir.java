package com.payneteasy.dcagent.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardCopyOption;

import static com.payneteasy.dcagent.util.SafeFiles.createDirs;
import static com.payneteasy.dcagent.util.SafeFiles.listFiles;
import static java.nio.file.Files.copy;

public class CopyDir {

    public static void copyDir(File aFrom, File aTo) {
        createDirs(aTo);

        for (File fileOrDir : listFiles(aFrom, pathname -> true)) {
            File targetFileOrDir = new File(aTo, fileOrDir.getName());
            if (fileOrDir.isDirectory()) {
                copyDir(fileOrDir, targetFileOrDir);
            } else {
                copyFile(fileOrDir, targetFileOrDir);
            }
        }
    }

    public static void copyFile(File aFrom, File aTo) {
        try {
            copy(aFrom.toPath(), aTo.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy "
                    + aFrom.getAbsolutePath()
                    + " to "
                    + aTo.getAbsolutePath(), e
            );
        }
    }
}
