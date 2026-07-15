package com.payneteasy.dcagent.core.config.model.docker.volumes;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class FileConfigVolume implements IVolume {
    @Getter(onMethod_ = @Override) String  source;
    @Getter(onMethod_ = @Override) String  destination;
    @Getter(onMethod_ = @Override) boolean readonly;
    String  configPath;
}
