package com.payneteasy.dcagent.controlplane;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.controlplane.service.command.CommandListService;
import com.payneteasy.dcagent.controlplane.service.command.CommandWriteService;
import com.payneteasy.dcagent.controlplane.service.command.ConfigBackupService;
import com.payneteasy.dcagent.controlplane.service.command.CommandWriteService.CommandSaveResult;
import com.payneteasy.dcagent.controlplane.service.command.CommandWriteService.Mode;
import com.payneteasy.dcagent.controlplane.service.serviceview.ServiceViewDelegate;
import com.payneteasy.dcagent.controlplane.service.supervise.ISuperviseService;
import com.payneteasy.dcagent.metrics.SystemInfoCollector;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;

import java.util.List;

public class DcAgentControlPlaneRemoteServiceImpl implements IDcAgentControlPlaneRemoteService {

    private final ISuperviseService     superviseService;
    private final ServiceViewDelegate   serviceViewDelegate;
    private final CommandListService    commandListService;
    private final CommandWriteService   commandWriteService;
    private final ConfigBackupService   configBackupService;
    private final SystemInfoCollector   systemInfoCollector;

    public DcAgentControlPlaneRemoteServiceImpl(
              ISuperviseService   daemontoolsService
            , ServiceViewDelegate serviceViewDelegate
            , CommandListService  commandListService
            , CommandWriteService commandWriteService
            , ConfigBackupService configBackupService
            , SystemInfoCollector systemInfoCollector
    ) {
        this.superviseService    = daemontoolsService;
        this.serviceViewDelegate = serviceViewDelegate;
        this.commandListService  = commandListService;
        this.commandWriteService = commandWriteService;
        this.configBackupService = configBackupService;
        this.systemInfoCollector = systemInfoCollector;
    }

    @Override
    public ServiceListResponse listServices(ServiceListRequest aRequest) {
        List<ServiceInfoItem> services = superviseService.listServices(VoidRequest.VOID_REQUEST);

        return ServiceListResponse.builder()
                .services(services)
                .build();
    }

    @Override
    public ServiceViewResponse viewService(ServiceViewRequest aRequest) {
        return serviceViewDelegate.getServiceView(aRequest.getServiceName());
    }

    @Override
    public ServiceActionResponse sendAction(ServiceActionRequest aRequest) {
        superviseService.sendAction(aRequest.getServiceName(), aRequest.getServiceAction());
        return ServiceActionResponse.builder().build();
    }

    @Override
    public CommandListResponse listCommands(CommandListRequest aRequest) {
        return CommandListResponse.builder()
                .commands(commandListService.listCommands())
                .build();
    }

    @Override
    public ConfigBackupResponse backupConfigs(ConfigBackupRequest aRequest) {
        return ConfigBackupResponse.builder()
                .files(configBackupService.listConfigFiles())
                .build();
    }

    @Override
    public SystemInfoResponse getSystemInfo(SystemInfoRequest aRequest) {
        return SystemInfoResponse.builder()
                .systemInfo(systemInfoCollector.collect())
                .build();
    }

    @Override
    public CommandGetResponse getCommand(CommandGetRequest aRequest) {
        return CommandGetResponse.builder()
                .command(commandWriteService.getCommand(aRequest.getName()))
                .build();
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Override public CommandSaveResponse createJar(CommandJarRequest aRequest)                   { return writeJar(Mode.CREATE, aRequest); }
    @Override public CommandSaveResponse createWar(CommandWarRequest aRequest)                   { return writeWar(Mode.CREATE, aRequest); }
    @Override public CommandSaveResponse createNode(CommandNodeRequest aRequest)                 { return writeNode(Mode.CREATE, aRequest); }
    @Override public CommandSaveResponse createSaveArtifact(CommandSaveArtifactRequest aRequest) { return writeSaveArtifact(Mode.CREATE, aRequest); }
    @Override public CommandSaveResponse createZipArchive(CommandZipArchiveRequest aRequest)     { return writeZipArchive(Mode.CREATE, aRequest); }
    @Override public CommandSaveResponse createZipDirs(CommandZipDirsRequest aRequest)           { return writeZipDirs(Mode.CREATE, aRequest); }
    @Override public CommandSaveResponse createFetchUrl(CommandFetchUrlRequest aRequest)         { return writeFetchUrl(Mode.CREATE, aRequest); }
    @Override public CommandSaveResponse createDocker(CommandDockerRequest aRequest)             { return writeDocker(Mode.CREATE, aRequest); }

    // ── Update ─────────────────────────────────────────────────────────────

    @Override public CommandSaveResponse updateJar(CommandJarRequest aRequest)                   { return writeJar(Mode.UPDATE, aRequest); }
    @Override public CommandSaveResponse updateWar(CommandWarRequest aRequest)                   { return writeWar(Mode.UPDATE, aRequest); }
    @Override public CommandSaveResponse updateNode(CommandNodeRequest aRequest)                 { return writeNode(Mode.UPDATE, aRequest); }
    @Override public CommandSaveResponse updateSaveArtifact(CommandSaveArtifactRequest aRequest) { return writeSaveArtifact(Mode.UPDATE, aRequest); }
    @Override public CommandSaveResponse updateZipArchive(CommandZipArchiveRequest aRequest)     { return writeZipArchive(Mode.UPDATE, aRequest); }
    @Override public CommandSaveResponse updateZipDirs(CommandZipDirsRequest aRequest)           { return writeZipDirs(Mode.UPDATE, aRequest); }
    @Override public CommandSaveResponse updateFetchUrl(CommandFetchUrlRequest aRequest)         { return writeFetchUrl(Mode.UPDATE, aRequest); }
    @Override public CommandSaveResponse updateDocker(CommandDockerRequest aRequest)             { return writeDocker(Mode.UPDATE, aRequest); }

    // ── Per-type builders (force the type, inject the merged apiKeys) ────────

    private CommandSaveResponse writeJar(Mode aMode, CommandJarRequest aRequest) {
        TJarConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TJarConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.JAR, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.JAR).apiKeys(keys).build()));
    }

    private CommandSaveResponse writeWar(Mode aMode, CommandWarRequest aRequest) {
        TJarConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TJarConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.WAR, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.WAR).apiKeys(keys).build()));
    }

    private CommandSaveResponse writeNode(Mode aMode, CommandNodeRequest aRequest) {
        TJarConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TJarConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.NODE, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.NODE).apiKeys(keys).build()));
    }

    private CommandSaveResponse writeSaveArtifact(Mode aMode, CommandSaveArtifactRequest aRequest) {
        TSaveArtifactConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TSaveArtifactConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.SAVE_ARTIFACT, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.SAVE_ARTIFACT).apiKeys(keys).build()));
    }

    private CommandSaveResponse writeZipArchive(Mode aMode, CommandZipArchiveRequest aRequest) {
        TZipArchiveConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TZipArchiveConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.ZIP_ARCHIVE, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.ZIP_ARCHIVE).apiKeys(keys).build()));
    }

    private CommandSaveResponse writeZipDirs(Mode aMode, CommandZipDirsRequest aRequest) {
        TZipDirsConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TZipDirsConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.ZIP_DIRS, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.ZIP_DIRS).apiKeys(keys).build()));
    }

    private CommandSaveResponse writeFetchUrl(Mode aMode, CommandFetchUrlRequest aRequest) {
        TFetchUrlConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TFetchUrlConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.FETCH_URL, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.FETCH_URL).apiKeys(keys).build()));
    }

    private CommandSaveResponse writeDocker(Mode aMode, CommandDockerRequest aRequest) {
        TDockerConfig config = aRequest.getConfig() != null ? aRequest.getConfig() : TDockerConfig.builder().build();
        return toResponse(commandWriteService.save(TaskType.DOCKER, aMode, aRequest.getName(), aRequest.getApiKeys(),
                keys -> config.toBuilder().type(TaskType.DOCKER).apiKeys(keys).build()));
    }

    private static CommandSaveResponse toResponse(CommandSaveResult aResult) {
        return CommandSaveResponse.builder()
                .status(aResult.getStatus())
                .command(aResult.getCommand())
                .build();
    }
}
