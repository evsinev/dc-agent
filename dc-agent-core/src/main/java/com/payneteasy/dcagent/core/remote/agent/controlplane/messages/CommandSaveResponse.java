package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** Shared response for create and update. {@code command} is null unless the write succeeded. */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandSaveResponse {

    CommandSaveStatus status;
    CommandDetail     command;
}
