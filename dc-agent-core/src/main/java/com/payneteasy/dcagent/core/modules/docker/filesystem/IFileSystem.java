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

}
