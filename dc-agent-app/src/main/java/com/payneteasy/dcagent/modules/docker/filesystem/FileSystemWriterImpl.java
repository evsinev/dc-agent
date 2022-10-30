package com.payneteasy.dcagent.modules.docker.filesystem;

import com.payneteasy.dcagent.config.model.docker.Owner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static com.payneteasy.dcagent.util.SafeFiles.createDirs;
import static com.payneteasy.dcagent.util.SafeFiles.writeFile;
import static java.nio.charset.StandardCharsets.UTF_8;

public class FileSystemWriterImpl implements IFileSystem {

    private static final Logger LOG = LoggerFactory.getLogger(FileSystemWriterImpl.class);

    @Override
    public void createDirectories(Owner aOwner, File aDir) {
        LOG.debug("Creating directories {} ...", aDir.getAbsolutePath());
        createDirs(aDir);
    }

    @Override
    public void writeExecutable(Owner aOwner, File aFile, String aText) {
        LOG.debug("Writing file {} ...", aFile.getAbsolutePath());
        writeFile(aFile, aText.getBytes(UTF_8));

        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);

        try {
            Files.setPosixFilePermissions(aFile.toPath(), perms);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot set attributes to file " + aFile.getAbsolutePath(), e);
        }
    }
}
