package com.payneteasy.dcagent.modules.docker.filesystem;

import com.payneteasy.dcagent.config.model.docker.Owner;

import java.io.File;

public interface IFileSystem {

    void createDirectories(Owner aOwner, File aDir);

    void writeExecutable(Owner aOwner, File aFile, String aText);

    void copyDir(Owner aOwner, File aFrom, File aTo);

    void copyFile(Owner aOwner, File aFrom, File aTo);

    void writeFile(Owner aOwner, File aSource, byte[] body);
}
