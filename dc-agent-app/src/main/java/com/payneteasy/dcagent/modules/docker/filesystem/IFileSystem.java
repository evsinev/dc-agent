package com.payneteasy.dcagent.modules.docker.filesystem;

import com.payneteasy.dcagent.config.model.docker.Owner;

import java.io.File;

public interface IFileSystem {

    void createDirectories(Owner aOwner, File aDir);

    void writeExecutable(Owner aOwner, File aFile, String aText);

}
