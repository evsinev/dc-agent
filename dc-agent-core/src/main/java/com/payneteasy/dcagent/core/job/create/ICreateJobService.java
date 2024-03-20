package com.payneteasy.dcagent.core.job.create;

import com.payneteasy.dcagent.core.job.create.messages.CreateJobParam;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;

public interface ICreateJobService {

    TempFile createJob(CreateJobParam aJobParam);

}
