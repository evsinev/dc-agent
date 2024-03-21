package com.payneteasy.dcagent.core.util.zip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.payneteasy.dcagent.core.util.SafeFiles.ensureFileExists;
import static com.payneteasy.dcagent.core.util.Streams.readAllBytes;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ZipFileViewer {

    private static final Logger LOG = LoggerFactory.getLogger( ZipFileViewer.class );

    private final Map<String, ZipFileItem> items;

    public ZipFileViewer(File aZipFile) {
        items = parseZipFile(ensureFileExists(aZipFile));
    }

    private static Map<String, ZipFileItem> parseZipFile(File aZipFile) {
        Map<String, ZipFileItem> items = new HashMap<>();

        try (ZipFile zipFile = new ZipFile(aZipFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();

                if (zipEntry.isDirectory()) {
                    continue;
                }

                items.put(
                        zipEntry.getName()
                        , new ZipFileItem(
                                  zipEntry.getName()
                                , readAllBytes(zipFile.getInputStream(zipEntry))
                        )
                );

            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read zip file " + aZipFile.getAbsolutePath(), e);
        }

        return items;
    }

    public Optional<ZipFileItem> getItem(String aName) {
        return Optional.ofNullable(items.get(aName));
    }

    public String getItemText(String aName) {
        byte[] bytes = getItem(aName).orElseThrow(() -> new IllegalArgumentException("No item " + aName)).getBytes();
        return new String(bytes, UTF_8);
    }
}
