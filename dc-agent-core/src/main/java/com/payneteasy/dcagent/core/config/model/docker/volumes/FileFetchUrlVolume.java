package com.payneteasy.dcagent.core.config.model.docker.volumes;

import com.payneteasy.dcagent.core.config.model.docker.TSignatureType;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder(toBuilder = true)
public class FileFetchUrlVolume implements IVolume {
    @Getter(onMethod_ = @Override) String  source;
    @Getter(onMethod_ = @Override) String  destination;
    @Getter(onMethod_ = @Override) boolean readonly;

    String url;
    String version;

    TSignatureType signatureType;
}
