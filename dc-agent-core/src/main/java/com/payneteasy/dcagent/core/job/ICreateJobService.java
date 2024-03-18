package com.payneteasy.dcagent.core.job;

import com.payneteasy.dcagent.core.job.messages.CreateJobParam;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;

public interface ICreateJobService {

    TempFile createJob(CreateJobParam aJobParam);

}
