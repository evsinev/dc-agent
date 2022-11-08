package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.modules.docker.IActionLogger;

public interface IFileSystemFactory {

    IFileSystem createFileSystem(IActionLogger aLogger);

}
