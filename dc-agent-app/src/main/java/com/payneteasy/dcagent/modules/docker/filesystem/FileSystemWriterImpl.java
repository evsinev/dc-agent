package com.payneteasy.dcagent.modules.docker.filesystem;

import com.payneteasy.dcagent.config.model.docker.Owner;
import com.payneteasy.dcagent.modules.docker.IActionLogger;
import com.payneteasy.dcagent.util.FileCompare;
import com.payneteasy.dcagent.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static com.payneteasy.dcagent.util.FileCompare.isFileIdentical;
import static com.payneteasy.dcagent.util.SafeFiles.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.copy;

public class FileSystemWriterImpl implements IFileSystem {

    private final IActionLogger logger;

    public FileSystemWriterImpl(IActionLogger aLogger) {
        logger = aLogger;
    }

    @Override
    public void createDirectories(Owner aOwner, File aDir) {
        if(aDir.exists()) {
            return;
        }

        logger.info("\uD83D\uDCC1 Creating directories {} ...", aDir.getAbsolutePath()); // 📁
        createDirs(aDir);
    }

    @Override
    public void writeExecutable(Owner aOwner, File aFile, String aText) {
        writeFile(aOwner, aFile, aText.getBytes(UTF_8));

        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);

        Set<PosixFilePermission> existsPermissions;
        try {
            existsPermissions = Files.getPosixFilePermissions(aFile.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get attributes from file " + aFile.getAbsolutePath(), e);
        }

        if(perms.equals(existsPermissions)) {
            return;
        }

        logger.info("\uD83C\uDFBD Adding executable to {}", aFile.getAbsolutePath()); // 🎽

        try {
            Files.setPosixFilePermissions(aFile.toPath(), perms);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot set attributes to file " + aFile.getAbsolutePath(), e);
        }
    }

    @Override
    public void copyDir(Owner aOwner, File aFrom, File aTo) {
        createDirectories(aOwner, aTo);

        for (File fileOrDir : listFiles(aFrom, pathname -> true)) {
            File targetFileOrDir = new File(aTo, fileOrDir.getName());
            if (fileOrDir.isDirectory()) {
                copyDir(aOwner, fileOrDir, targetFileOrDir);
            } else {
                copyFile(aOwner, fileOrDir, targetFileOrDir);
            }
        }

    }

    @Override
    public void copyFile(Owner aOwner, File aFrom, File aTo) {
        if(isFileIdentical(aFrom, aTo)) {
            return;
        }
        try {
            logger.info("\uD83D\uDDC3️ Copy file {} to {} ...", aFrom.getAbsoluteFile(), aTo.getAbsolutePath()); // 🗃️

            copy(aFrom.toPath(), aTo.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy "
                    + aFrom.getAbsolutePath()
                    + " to "
                    + aTo.getAbsolutePath(), e
            );
        }
    }

    @Override
    public void writeFile(Owner aOwner, File aSource, byte[] body) {
        if(isFileIdentical(aSource, body)) {
            return;
        }

        logger.info("\uD83D\uDDC4️ Writing file {} ...", aSource.getAbsolutePath()); // 🗄️
        SafeFiles.writeFile(aSource, body);
    }
}
