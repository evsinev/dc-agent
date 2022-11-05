package com.payneteasy.dcagent.config.model.docker.volumes;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class LinkToHostDirectoryVolume implements IVolume {
    String  source;
    String  destination;
    boolean readonly;
}