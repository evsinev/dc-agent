package com.payneteasy.dcagent.core.config.model.docker;

import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder (toBuilder = true)
public class TDocker {
    String              version;
    String              name;
    DockerImage         image;
    List<BoundVariable> boundVariables;
    Map<String, String> boundVariablesMap;
    List<EnvVariable>   env;
    Map<String, String> envMap;
    List<DockerVolume>  volumes;
    String[]            args;
    Owner               owner;
    DockerDirectories   directories;
    TSecurityContext    securityContext;
}
