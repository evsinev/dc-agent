package com.payneteasy.dcagent.operator.service.appview.messages;

import com.payneteasy.dcagent.operator.service.appview.model.AppStatusType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class AppStatusResponse {
    AppStatusType status;
    String        errorMessage;
}
