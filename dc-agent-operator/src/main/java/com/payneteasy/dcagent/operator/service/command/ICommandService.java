package com.payneteasy.dcagent.operator.service.command;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.command.messages.*;

public interface ICommandService {

    CommandListResponse listCommands(VoidRequest aRequest);

    CommandDetailResponse getCommand(CommandGetRequest aRequest);

    CommandDetailResponse createJar(CommandJarRequest aRequest);

    CommandDetailResponse createWar(CommandWarRequest aRequest);

    CommandDetailResponse createNode(CommandNodeRequest aRequest);

    CommandDetailResponse createSaveArtifact(CommandSaveArtifactRequest aRequest);

    CommandDetailResponse createZipArchive(CommandZipArchiveRequest aRequest);

    CommandDetailResponse createZipDirs(CommandZipDirsRequest aRequest);

    CommandDetailResponse createFetchUrl(CommandFetchUrlRequest aRequest);

    CommandDetailResponse createDocker(CommandDockerRequest aRequest);

    CommandDetailResponse updateJar(CommandJarRequest aRequest);

    CommandDetailResponse updateWar(CommandWarRequest aRequest);

    CommandDetailResponse updateNode(CommandNodeRequest aRequest);

    CommandDetailResponse updateSaveArtifact(CommandSaveArtifactRequest aRequest);

    CommandDetailResponse updateZipArchive(CommandZipArchiveRequest aRequest);

    CommandDetailResponse updateZipDirs(CommandZipDirsRequest aRequest);

    CommandDetailResponse updateFetchUrl(CommandFetchUrlRequest aRequest);

    CommandDetailResponse updateDocker(CommandDockerRequest aRequest);
}
