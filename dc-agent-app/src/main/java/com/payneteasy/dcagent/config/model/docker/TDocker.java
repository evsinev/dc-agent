package com.payneteasy.dcagent.config.model.docker;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder (toBuilder = true)
public class TDocker {
    String              version;
    String              name;
    DockerImage         image;
    List<BoundVariable> boundVariables;
    List<EnvVariable>   env;
    List<DockerVolume>  volumes;
    String[]            args;
    Owner               owner;
    DockerDirectories   directories;
}
