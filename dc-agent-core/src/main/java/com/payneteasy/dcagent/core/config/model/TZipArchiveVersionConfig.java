package com.payneteasy.dcagent.core.config.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

/**
 * {@code zip-archive-version}: publish a versioned directory, switch a pointer file and wait for
 * the service's reload. Raw values as written in the config — sizes ({@code 50mb}), counts
 * ({@code 10k}) and durations ({@code 5m}) are strings; they are checked by
 * {@code ZipArchiveVersionSettings.from} right after the call is authorized.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder(toBuilder = true)
public class TZipArchiveVersionConfig implements IApiKeys, IGetTaskType {

    @Getter(onMethod_ = @Override) TaskType            type;
    @Getter(onMethod_ = @Override) Map<String, String> apiKeys;

    String              dir;
    String              versionFile;
    String              versionPattern;
    String              maxUploadBytes;
    String              maxBytes;
    String              maxEntries;
    String              reloadUrl;
    Map<String, String> reloadHeaders;
    String              reloadBody;
    String              waitTimeout;
}
