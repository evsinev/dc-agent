package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.EnvVariable;
import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.payneteasy.dcagent.core.util.SaveList.safeList;

public class DockerResolver {

    private static final Logger LOG = LoggerFactory.getLogger( DockerResolver.class );

    private final VolumesResolver        volumesResolver        = new VolumesResolver();
    private final BoundVariablesResolver boundVariablesResolver = new BoundVariablesResolver();

    public TDocker resolve(TDocker aUnresolved, File aUploadedDir, IFileSystem aFilesystem, IActionLogger aLogger) {

        List<BoundVariable> boundVariables = boundVariablesResolver.mergeVariables(aUnresolved.getBoundVariables(), aUnresolved.getBoundVariablesMap());

        return aUnresolved.toBuilder()
                .volumes(
                        volumesResolver.resolveVolumes(
                              aUnresolved.getVolumes()
                            , aUploadedDir
                            , aUnresolved.getDirectories()
                            , aFilesystem
                            , aLogger
                            , boundVariables
                        )
                )
                .owner          ( resolveOwner(aUnresolved.getOwner()))
                .boundVariables ( boundVariables )
                .env            ( mergeEnv(aUnresolved.getEnv(), aUnresolved.getEnvMap()))
                .build();
    }

    private List<EnvVariable> mergeEnv(List<EnvVariable> aList, Map<String, String> aMap) {
        if(aMap == null) {
            return aList;
        }

        List<EnvVariable> ret = new ArrayList<>(safeList(aList));
        for (Map.Entry<String, String> entry : aMap.entrySet()) {
            ret.add(EnvVariable.builder()
                            .name(entry.getKey())
                            .value(entry.getValue())
                    .build());
        }
        return ret;
    }

    private Owner resolveOwner(Owner owner) {
        if(owner != null) {
            return owner;
        }

        return Owner.builder()
                .user("root")
                .group("root")
                .build();

    }
}
