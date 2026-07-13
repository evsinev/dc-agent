package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** {@code command} is null when no config file with that name exists (mapped to 404 upstream). */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandGetResponse {

    CommandDetail command;
}
