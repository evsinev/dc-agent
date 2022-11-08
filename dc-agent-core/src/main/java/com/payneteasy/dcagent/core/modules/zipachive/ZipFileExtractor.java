package com.payneteasy.dcagent.core.modules.zipachive;

import com.payneteasy.dcagent.core.util.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public class ZipFileExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(ZipFileExtractor.class);

    public void extractZip(File aZipFile, File aTargetDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(aZipFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                if (zipEntry.isDirectory()) {
                    continue;
                }
                InputStream in   = zipFile.getInputStream(zipEntry);
                File        file = new File(aTargetDir, zipEntry.getName());
                makeDirForFile(file);
                LOG.debug("Extracting {} to {} ...", zipEntry.getName(), file.getAbsolutePath());
                try (FileOutputStream out = new FileOutputStream(file)) {
                    Streams.copy(in, out);
                }
            }
        }
    }

    private void makeDirForFile(File aFile) throws IOException {
        File dir = aFile.getParentFile();
        if (dir.exists()) {
            return;
        }
        LOG.debug("Creating directory {} ...", dir.getAbsolutePath());
        Files.createDirectories(dir.toPath());
    }


}