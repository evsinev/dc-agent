package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Records every file system call instead of doing it. */
public class RecordingFileSystem implements IFileSystem {

    public final List<String> calls = new ArrayList<>();

    @Override
    public void createDirectories(Owner aOwner, File aDir) {
        calls.add("createDirectories " + aDir);
    }

    @Override
    public void writeExecutable(Owner aOwner, File aFile, String aText) {
        calls.add("writeExecutable " + aFile);
    }

    @Override
    public void copyDir(Owner aOwner, File aFrom, File aTo) {
        calls.add("copyDir " + aTo);
    }

    @Override
    public void copyFile(Owner aOwner, File aFrom, File aTo) {
        calls.add("copyFile " + aTo);
    }

    @Override
    public void writeFile(Owner aOwner, File aSource, byte[] body) {
        calls.add("writeFile " + aSource);
    }

    @Override
    public void applyOwner(File aDir, TVolumeOwner aResolvedOwner, String aMode) {
        calls.add("applyOwner " + aDir + " " + aResolvedOwner + " " + aMode);
    }

    @Override
    public void copyTemplateFile(Owner aOwner, File aFrom, File aTo, List<BoundVariable> aVariabled) {
        calls.add("copyTemplateFile " + aTo);
    }
}
