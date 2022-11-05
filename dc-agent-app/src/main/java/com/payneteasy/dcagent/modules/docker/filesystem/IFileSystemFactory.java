package com.payneteasy.dcagent.modules.docker.filesystem;

import com.payneteasy.dcagent.modules.docker.IActionLogger;

public interface IFileSystemFactory {

    IFileSystem createFileSystem(IActionLogger aLogger);

}
