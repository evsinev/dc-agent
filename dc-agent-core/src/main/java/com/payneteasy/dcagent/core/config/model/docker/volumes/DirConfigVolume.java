package com.payneteasy.dcagent.core.config.model.docker.volumes;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class DirConfigVolume implements IVolume {
    String  source;
    String  destination;
    boolean readonly;
    String  configPath;
}
