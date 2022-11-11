package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.Owner;

import java.io.File;
import java.util.List;

public interface IFileSystem {

    void createDirectories(Owner aOwner, File aDir);

    void writeExecutable(Owner aOwner, File aFile, String aText);

    void copyDir(Owner aOwner, File aFrom, File aTo);

    void copyFile(Owner aOwner, File aFrom, File aTo);

    void writeFile(Owner aOwner, File aSource, byte[] body);

    void copyTemplateFile(Owner aOwner, File aFrom, File aTo, List<BoundVariable> aVariabled);

}
