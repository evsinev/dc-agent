package com.payneteasy.dcagent.operator.service.taskcreate;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;

public interface ITaskCreateService {

    TempFile createTaskZipFile(String aTaskName);

}
