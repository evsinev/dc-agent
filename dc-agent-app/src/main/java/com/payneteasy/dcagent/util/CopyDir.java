package com.payneteasy.dcagent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardCopyOption;

import static com.payneteasy.dcagent.util.SafeFiles.createDirs;
import static com.payneteasy.dcagent.util.SafeFiles.listFiles;
import static java.nio.file.Files.copy;

public class CopyDir {

    private static final Logger LOG = LoggerFactory.getLogger( CopyDir.class );

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
            LOG.debug("Copy file {} to {} ...", aFrom.getAbsoluteFile(), aTo.getAbsolutePath());
            
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
