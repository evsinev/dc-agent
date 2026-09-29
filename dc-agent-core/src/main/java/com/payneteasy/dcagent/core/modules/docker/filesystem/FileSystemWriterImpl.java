package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.modules.docker.HandlebarProcessor;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import com.payneteasy.dcagent.core.modules.docker.preflight.PathAttributes;
import com.payneteasy.dcagent.core.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.payneteasy.dcagent.core.util.FileCompare.isFileIdentical;
import static com.payneteasy.dcagent.core.util.SafeFiles.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.copy;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public class FileSystemWriterImpl implements IFileSystem {

    private final IActionLogger      logger;
    private final HandlebarProcessor handlebars = new HandlebarProcessor();

    public FileSystemWriterImpl(IActionLogger aLogger) {
        logger = aLogger;
    }

    @Override
    public void createDirectories(Owner aOwner, File aDir) {
        if(aDir.exists()) {
            return;
        }

        logger.info("\uD83D\uDCC1  Creating directories {} ...", aDir.getAbsolutePath()); // 📁
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

        logger.info("\uD83C\uDFBD  Adding executable to {}", aFile.getAbsolutePath()); // 🎽

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
            logger.info("\uD83D\uDDC3️  Copy file {} to {} ...", aFrom.getAbsoluteFile(), aTo.getAbsolutePath()); // 🗃️

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

        logger.info("\uD83D\uDDC4️  Writing file {} ...", aSource.getAbsolutePath()); // 🗄️
        SafeFiles.writeFile(aSource, body);
    }

    @Override
    public void copyTemplateFile(Owner aOwner, File aFrom, File aTo, List<BoundVariable> aVariables) {
        String text = handlebars.processTemplate(aFrom, aVariables);
        byte[] body = text.getBytes(UTF_8);

        if(isFileIdentical(aTo, body)) {
            return;
        }

        logger.info("⚜️️  Writing template from {} to {} ...", aFrom.getName(), aTo.getAbsolutePath()); // ⚜️
        SafeFiles.writeFile(aTo, body);
    }

    @Override
    public void applyOwner(File aDir, TVolumeOwner aResolvedOwner, String aMode) {
        Path        path   = aDir.toPath();
        OwnerChange change = OwnerChange.of(existingDirectory(path), aResolvedOwner, aMode);
        if (change.isEmpty()) {
            return;
        }

        logger.info("\uD83D\uDD11  Changing {} of {} ...", change.describe(), path); // 🔑

        try {
            // NOFOLLOW_LINKS: lchown and open(O_NOFOLLOW) + fchmod in the JDK
            if (change.uid() != null) {
                Files.setAttribute(path, "unix:uid", change.uid(), NOFOLLOW_LINKS);
            }
            if (change.gid() != null) {
                Files.setAttribute(path, "unix:gid", change.gid(), NOFOLLOW_LINKS);
            }
            if (change.permissions() != null) {
                Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS).setPermissions(change.permissions());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot change " + change.describe() + " of " + path
                    + " (changing the owner needs the agent to run as root): " + e.getMessage(), e);
        }
    }

    static PathAttributes existingDirectory(Path aPath) {
        PathAttributes attributes;
        try {
            attributes = PathAttributes.LSTAT.read(aPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read attributes of " + aPath, e);
        }
        if (attributes == null) {
            throw new IllegalStateException("Cannot change owner/mode: " + aPath + " does not exist");
        }
        if (!attributes.isDirectory()) {
            throw new IllegalStateException("Cannot change owner/mode: " + aPath + " is not a directory");
        }
        return attributes;
    }

    @Override
    public void writeFileWithMode(File aFile, byte[] aBody, String aMode) {
        ModeFiles.refuseLink(aFile);
        boolean contentSame = isFileIdentical(aFile, aBody);
        if (contentSame && ModeFiles.hasMode(aFile, aMode)) {
            return;
        }
        if (!contentSame) {
            logger.info("\uD83D\uDDC4️  Writing file {} (mode {}) ...", aFile.getAbsolutePath(), aMode); // 🗄️
            ModeFiles.writeNoFollow(aFile, aBody);
        }
        ModeFiles.setMode(aFile, aMode);
    }

    @Override
    public void deleteFileIfExists(File aFile) {
        try {
            if (java.nio.file.Files.deleteIfExists(aFile.toPath())) {
                logger.info("\uD83D\uDDD1️  Deleted {}", aFile.getAbsolutePath()); // 🗑️
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot delete " + aFile.getAbsolutePath(), e);
        }
    }
}
