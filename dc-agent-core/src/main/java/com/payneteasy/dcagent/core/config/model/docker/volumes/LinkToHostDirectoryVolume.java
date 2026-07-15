package com.payneteasy.dcagent.core.config.model.docker.volumes;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
@Getter(onMethod_ = @Override)
public class LinkToHostDirectoryVolume implements IVolume {
    String  source;
    String  destination;
    boolean readonly;
}
