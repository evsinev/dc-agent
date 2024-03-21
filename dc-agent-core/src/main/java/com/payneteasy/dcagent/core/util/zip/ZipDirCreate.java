package com.payneteasy.dcagent.core.util.zip;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.payneteasy.dcagent.core.util.SafeFiles.listFiles;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;

public class ZipDirCreate {

    private static final Logger LOG = LoggerFactory.getLogger( ZipDirCreate.class );

    private File   baseDir;
    private String firstSegment;

    public ZipDirCreate baseDir(File baseDir) {
        this.baseDir = baseDir;
        return this;
    }

    public ZipDirCreate firstSegment(String firstSegment) {
        this.firstSegment = firstSegment;
        return this;
    }

    public TempFile createZipFile(TempFile aTempFile) {
        createZipFile(aTempFile.getFile());
        return aTempFile;
    }

    public File createZipFile(File aFile) {
        Objects.requireNonNull(baseDir, "Base dir is null");
        try(ZipOutputStream out = new ZipOutputStream(newOutputStream(aFile.toPath()))) {
            addDir(out, baseDir);
            return aFile;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write zip to file " + aFile.getAbsolutePath(), e);
        }
    }

    private void addDir(ZipOutputStream out, File aDir) {
        for (File fileOrDir : listFiles(aDir, File::canRead)) {
            if(fileOrDir.isFile()) {
                addFile(out, fileOrDir);
            } else {
                addDir(out, fileOrDir);
            }
        }
    }

    private void addFile(ZipOutputStream out, File file) {
        String entryName = getEntryName(file);
        try {
            out.putNextEntry(new ZipEntry(entryName));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot put zip entry " + entryName, e);
        }
        try {
            out.write(readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.getAbsolutePath(), e);
        }
        try {
            out.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close zip entry " + entryName, e);
        }
    }

    private String getEntryName(File aFile) {
        String basePath  = baseDir.getAbsolutePath();
        String filePath  = aFile.getAbsolutePath();
        String entryName = firstSegment + filePath.substring(basePath.length());
        LOG.trace("Entry is {}\n for file {}", entryName, filePath);
        return entryName;
    }


}
