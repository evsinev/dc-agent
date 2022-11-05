package com.payneteasy.dcagent.modules.docker.filesystem;

import com.payneteasy.dcagent.config.model.docker.Owner;
import com.payneteasy.dcagent.modules.docker.IActionLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static com.payneteasy.dcagent.util.FileCompare.isFileIdentical;
import static com.payneteasy.dcagent.util.SafeFiles.listFiles;
import static java.nio.charset.StandardCharsets.UTF_8;

public class FileSystemCheckImpl implements IFileSystem {

    private final IActionLogger logger;

    public FileSystemCheckImpl(IActionLogger aLogger) {
        logger = aLogger;
    }

    @Override
    public void createDirectories(Owner aOwner, File aDir) {
        if (aDir.exists()) {
            return;
        }

        logger.info("\uD83D\uDCC1 Will create directory {} ...", aDir.getAbsolutePath()); // 📁
    }

    @Override
    public void writeExecutable(Owner aOwner, File aFile, String aText) {
        writeFile(aOwner, aFile, aText.getBytes(UTF_8));

        if(aFile.exists()) {
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

            if (perms.equals(existsPermissions)) {
                return;
            }
        }

        logger.info("\uD83C\uDFBD Will add executable to {}", aFile.getAbsolutePath()); // 🎽
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
        if (isFileIdentical(aFrom, aTo)) {
            return;
        }
        logger.info("\uD83D\uDDC3️ Will copy file {} to {} ...", aFrom.getAbsoluteFile(), aTo.getAbsolutePath()); // 🗃️
    }

    @Override
    public void writeFile(Owner aOwner, File aSource, byte[] body) {
        if (isFileIdentical(aSource, body)) {
            return;
        }

        logger.info("\uD83D\uDDC4️ Will write file {} ...", aSource.getAbsolutePath()); // 🗄️
    }
}
