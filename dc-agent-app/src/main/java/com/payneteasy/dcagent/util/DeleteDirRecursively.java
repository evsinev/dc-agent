package com.payneteasy.dcagent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class DeleteDirRecursively {

    private static final Logger LOG = LoggerFactory.getLogger(DeleteDirRecursively.class);

    private static final File[] NO_FILES = new File[0];

    private final File sentinelDir;

    public DeleteDirRecursively(File aSentinelDir) {
        sentinelDir = aSentinelDir;
    }

    public void deleteDir(File aDir) {
        deleteDir(aDir, "");
    }

    public void deleteDirIfExists(File aDir) {
        if(aDir.exists()) {
            deleteDir(aDir);
        }
    }

    private void deleteDir(File aDir, String aIndent) {
        LOG.debug("Deleting dir {} ...", aDir.getAbsolutePath());
        for (File file : safe(aDir.listFiles())) {
            if (file.isDirectory()) {
                deleteDir(file, aIndent + "    ");
            } else {
                deleteWithSentinel(file);
            }
        }
        deleteWithSentinel(aDir);
    }

    private void deleteWithSentinel(File aFile) {
        String path = aFile.getAbsolutePath();
        if (path.length() < sentinelDir.getAbsolutePath().length()) {
            throw new IllegalStateException("You are going to delete " + path + " in a wrong dir");
        }

        if (!path.startsWith(sentinelDir.getAbsolutePath())) {
            throw new IllegalStateException("You are going to delete " + path + " in a wrong dir."
                    + "\n Dir should start with " + sentinelDir.getAbsolutePath()
                    + "\n But started      with " + path
            );
        }


        try {
            Files.delete(aFile.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + path, e);
        }
    }

    private File[] safe(File[] aFiles) {
        return aFiles != null ? aFiles : NO_FILES;
    }

}
