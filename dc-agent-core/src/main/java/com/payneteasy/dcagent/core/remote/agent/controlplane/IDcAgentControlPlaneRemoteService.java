package com.payneteasy.dcagent.core.remote.agent.controlplane;

import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;

public interface IDcAgentControlPlaneRemoteService {

    ServiceListResponse listServices(ServiceListRequest aRequest);

    ServiceViewResponse viewService(ServiceViewRequest aRequest);

    ServiceActionResponse sendAction(ServiceActionRequest aRequest);

    CommandListResponse listCommands(CommandListRequest aRequest);

    ConfigBackupResponse backupConfigs(ConfigBackupRequest aRequest);

    SystemInfoResponse getSystemInfo(SystemInfoRequest aRequest);

    CommandGetResponse getCommand(CommandGetRequest aRequest);

    CommandSaveResponse createJar(CommandJarRequest aRequest);

    CommandSaveResponse createWar(CommandWarRequest aRequest);

    CommandSaveResponse createNode(CommandNodeRequest aRequest);

    CommandSaveResponse createSaveArtifact(CommandSaveArtifactRequest aRequest);

    CommandSaveResponse createZipArchive(CommandZipArchiveRequest aRequest);

    CommandSaveResponse createZipDirs(CommandZipDirsRequest aRequest);

    CommandSaveResponse createFetchUrl(CommandFetchUrlRequest aRequest);

    CommandSaveResponse createDocker(CommandDockerRequest aRequest);

    CommandSaveResponse updateJar(CommandJarRequest aRequest);

    CommandSaveResponse updateWar(CommandWarRequest aRequest);

    CommandSaveResponse updateNode(CommandNodeRequest aRequest);

    CommandSaveResponse updateSaveArtifact(CommandSaveArtifactRequest aRequest);

    CommandSaveResponse updateZipArchive(CommandZipArchiveRequest aRequest);

    CommandSaveResponse updateZipDirs(CommandZipDirsRequest aRequest);

    CommandSaveResponse updateFetchUrl(CommandFetchUrlRequest aRequest);

    CommandSaveResponse updateDocker(CommandDockerRequest aRequest);

}
