package com.payneteasy.dcagent.admin.service.messages.save;

import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class SaveArtifactConfigSaveRequest {
    TSaveArtifactConfig saveArtifactConfig;
}
