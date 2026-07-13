package com.payneteasy.dcagent.operator.service.command.messages;

import com.payneteasy.dcagent.operator.service.command.model.TCommandDetail;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** Response for get/create/update — the resulting command detail (masked keys). */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandDetailResponse {

    TCommandDetail command;
}
