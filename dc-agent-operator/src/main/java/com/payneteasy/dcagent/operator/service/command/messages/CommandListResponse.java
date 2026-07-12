package com.payneteasy.dcagent.operator.service.command.messages;

import com.payneteasy.dcagent.operator.service.command.model.TCommandInfo;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandListResponse {

    List<TCommandInfo> commands;
}
