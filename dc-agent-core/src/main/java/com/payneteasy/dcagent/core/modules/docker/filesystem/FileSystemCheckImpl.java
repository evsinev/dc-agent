package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.modules.docker.HandlebarProcessor;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import com.payneteasy.dcagent.core.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.payneteasy.dcagent.core.util.FileCompare.isFileIdentical;
import static com.payneteasy.dcagent.core.util.SafeFiles.listFiles;
import static java.nio.charset.StandardCharsets.UTF_8;

public class FileSystemCheckImpl implements IFileSystem {

    private final IActionLogger      logger;
    private final HandlebarProcessor handlebars = new HandlebarProcessor();

    public FileSystemCheckImpl(IActionLogger aLogger) {
        logger = aLogger;
    }

    @Override
    public void createDirectories(Owner aOwner, File aDir) {
        if (aDir.exists()) {
            return;
        }

        logger.info("\uD83D\uDCC1  Will create directory {} ...", aDir.getAbsolutePath()); // 📁
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

        logger.info("\uD83C\uDFBD  Will add executable to {}", aFile.getAbsolutePath()); // 🎽
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
        logger.info("\uD83D\uDDC3️  Will copy file {} to {} ...", aFrom.getAbsoluteFile(), aTo.getAbsolutePath()); // 🗃️
    }

    @Override
    public void writeFile(Owner aOwner, File aSource, byte[] body) {
        if (isFileIdentical(aSource, body)) {
            return;
        }

        logger.info("\uD83D\uDDC4️  Will write file {} ...", aSource.getAbsolutePath()); // 🗄️
    }

    @Override
    public void copyTemplateFile(Owner aOwner, File aFrom, File aTo, List<BoundVariable> aVariables) {
        String text = handlebars.processTemplate(aFrom, aVariables);
        byte[] body = text.getBytes(UTF_8);

        if(isFileIdentical(aTo, body)) {
            return;
        }


        logger.info("⚜️️  Will write template file from {} to {} ...", aFrom.getName(), aTo.getAbsolutePath()); // ⚜️
        SafeFiles.writeFile(aTo, body);
    }
}
