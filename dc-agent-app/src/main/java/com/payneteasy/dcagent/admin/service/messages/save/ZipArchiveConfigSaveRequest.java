package com.payneteasy.dcagent.admin.service.messages.save;

import com.payneteasy.dcagent.config.model.TZipArchiveConfig;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ZipArchiveConfigSaveRequest {
    TZipArchiveConfig zipArchiveConfig;
}