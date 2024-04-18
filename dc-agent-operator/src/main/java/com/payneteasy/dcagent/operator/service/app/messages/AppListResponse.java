package com.payneteasy.dcagent.operator.service.app.messages;

import com.payneteasy.dcagent.operator.service.app.model.TApp;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class AppListResponse {
    List<TApp> apps;
}
