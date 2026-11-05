package com.payneteasy.dcagent.operator.service.taskcreate.impl;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.zip.ZipDirCreate;
import com.payneteasy.dcagent.operator.service.taskcreate.ITaskCreateService;

import java.io.File;
import java.io.IOException;

import static com.payneteasy.dcagent.core.util.SafeFiles.ensureDirExists;

public class TaskCreateServiceImpl implements ITaskCreateService {

    private final File tasksDir;

    public TaskCreateServiceImpl(File tasksDir) {
        this.tasksDir = tasksDir;
    }

    @Override
    public TempFile createTaskZipFile(String aTaskName) {
        File taskDir = ensureDirExists(new File(tasksDir, aTaskName));

        TempFile zip = new TempFile("task-" + aTaskName, "zip");
        try {
            return new ZipDirCreate()
                    .baseDir       ( taskDir )
                    .createZipFile ( zip     );
        } catch (RuntimeException e) {
            // the caller never gets the file to close
            try {
                zip.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }
}
