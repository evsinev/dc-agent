package com.payneteasy.dcagent.core.modules.zipversion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/** ZIPs for the disk tests: files only, in the order given; stored (not deflated) when asked. */
final class ZipFixtures {

    private ZipFixtures() {
    }

    static Path zip(Path aFile, Map<String, String> aFiles) throws IOException {
        return zip(aFile, aFiles, false);
    }

    static Path zip(Path aFile, Map<String, String> aFiles, boolean aStored) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(aFile))) {
            for (Map.Entry<String, String> file : aFiles.entrySet()) {
                byte[]   data  = file.getValue().getBytes(UTF_8);
                ZipEntry entry = new ZipEntry(file.getKey());
                if (aStored) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    entry.setCompressedSize(data.length);
                    entry.setCrc(crc.getValue());
                }
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
        }
        return aFile;
    }

    static VersionArchive archive(Path aZip) throws IOException {
        return VersionArchive.read(aZip, 10 * 1024 * 1024, 1000);
    }
}
