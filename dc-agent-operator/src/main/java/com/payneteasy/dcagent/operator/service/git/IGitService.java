package com.payneteasy.dcagent.operator.service.git;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.git.messages.GitLogResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitPullResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitStatusResponse;

public interface IGitService {

    GitLogResponse log(VoidRequest aRequest);

    GitPullResponse pull(VoidRequest aRequest);

    GitStatusResponse status(VoidRequest aRequest);

}
