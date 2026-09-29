package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;

import java.io.File;
import java.util.List;

public interface IFileSystem {

    void createDirectories(Owner aOwner, File aDir);

    void writeExecutable(Owner aOwner, File aFile, String aText);

    void copyDir(Owner aOwner, File aFrom, File aTo);

    void copyFile(Owner aOwner, File aFrom, File aTo);

    void writeFile(Owner aOwner, File aSource, byte[] body);

    void copyTemplateFile(Owner aOwner, File aFrom, File aTo, List<BoundVariable> aVariabled);

    /**
     * Sets owner and/or permissions of an existing directory without following a symbolic link.
     * Called only for a {@code directoryOrCreate} volume with owner/mode, after WritePathPreflight.
     *
     * @param aDir           physical path (no symbolic links, no {@code ..})
     * @param aResolvedOwner numbers only; {@code null} or a {@code null} field — keep
     * @param aMode          e.g. {@code "0770"}; {@code null} — keep
     */
    void applyOwner(File aDir, TVolumeOwner aResolvedOwner, String aMode);


    /**
     * A file the agent owns (e.g. {@code container-passwd}): written without following a symbolic
     * link at its place, then its permissions are set to {@code aMode} (not left to the umask). CHECK
     * reports a changed content or mode.
     *
     * @param aMode e.g. {@code "0644"}
     */
    void writeFileWithMode(File aFile, byte[] aBody, String aMode);

    /** Deletes the file (a link itself, never its target) when it exists. */
    void deleteFileIfExists(File aFile);
}
