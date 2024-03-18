package com.payneteasy.dcagent.core.util;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;

public class ZipFileBuilder {

    private final List<Item> items = new ArrayList<>();

    public static ZipFileBuilder buildZipFile() {
        return new ZipFileBuilder();
    }

    public ZipFileBuilder add(String aName, File aFile) {
        items.add(new Item(aName, aFile, null));
        return this;
    }

    public ZipFileBuilder add(String aName, byte[] aBytes) {
        items.add(new Item(aName, null, aBytes));
        return this;
    }

    public ZipFileBuilder add(String aName, String aContent) {
        items.add(new Item(aName, null, aContent.getBytes(UTF_8)));
        return this;
    }

    public TempFile build(TempFile aTempFile) {
        build(aTempFile.getFile());
        return aTempFile;
    }

    public File build(File aZipFile) {
        try(ZipOutputStream out = new ZipOutputStream(newOutputStream(aZipFile.toPath()), UTF_8)) {
            for (Item item : items) {
                out.putNextEntry(new ZipEntry(item.name));
                out.write(item.getBytes());
                out.closeEntry();
            }
            return aZipFile;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write to " + aZipFile.getAbsolutePath(), e);
        }
    }

    private static class Item {
        private final String name;
        private final File   file;
        private final byte[] bytes;

        private Item(String name, File file, byte[] bytes) {
            this.name  = name;
            this.file  = file;
            this.bytes = bytes;
        }

        public byte[] getBytes() {
            if(file != null) {
                try {
                    return readAllBytes(file.toPath());
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + file.getAbsolutePath(), e);
                }
            }

            return bytes;
        }
    }
}
