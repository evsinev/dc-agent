package com.payneteasy.dcagent.operator.service.command;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandListResponse;

public interface ICommandService {

    CommandListResponse listCommands(VoidRequest aRequest);

}
