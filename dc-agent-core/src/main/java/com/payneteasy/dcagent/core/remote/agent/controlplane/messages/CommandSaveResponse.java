package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * Shared response for create and update. {@code command} is null unless the write succeeded;
 * {@code message} is set for {@link CommandSaveStatus#INVALID} only.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandSaveResponse {

    CommandSaveStatus status;
    CommandDetail     command;
    String            message;
}
