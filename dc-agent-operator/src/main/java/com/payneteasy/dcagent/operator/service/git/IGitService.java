package com.payneteasy.dcagent.operator.service.git;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.git.messages.GitLogResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitPullResponse;

public interface IGitService {

    GitLogResponse log(VoidRequest aRequest);

    GitPullResponse pull(VoidRequest aRequest);

}
