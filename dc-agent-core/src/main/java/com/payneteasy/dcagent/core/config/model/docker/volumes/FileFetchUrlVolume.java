package com.payneteasy.dcagent.core.config.model.docker.volumes;

import com.payneteasy.dcagent.core.config.model.docker.TSignatureType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder(toBuilder = true)
public class FileFetchUrlVolume implements IVolume {
    String  source;
    String  destination;
    boolean readonly;

    String url;
    String version;

    TSignatureType signatureType;
}
