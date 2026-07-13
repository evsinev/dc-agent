package com.payneteasy.dcagent.operator.service.command.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandGetResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandSaveResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandInfoItem;
import com.payneteasy.dcagent.operator.service.command.ICommandService;
import com.payneteasy.dcagent.operator.service.command.error.CommandApiError;
import com.payneteasy.dcagent.operator.service.command.messages.*;
import com.payneteasy.dcagent.operator.service.command.model.TCommandDetail;
import com.payneteasy.dcagent.operator.service.command.model.TCommandInfo;
import com.payneteasy.dcagent.operator.service.command.validation.CommandValidator;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.mini.core.error.exception.ApiErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

/**
 * Lists the commands configured on each agent (fan-out) and proxies single-command get/create/update
 * to one agent. The agent returns conflict/not-found as a status in the body (its error handler
 * collapses thrown exceptions to 500); here that status is translated into the proper HTTP status
 * (409/404) and an unreachable agent into 502, so the frontend sees actionable errors.
 */
public class CommandServiceImpl implements ICommandService {

    private static final Logger             LOG          = LoggerFactory.getLogger(CommandServiceImpl.class);
    private static final int                MAX_THREADS  = 8;
    private static final CommandListRequest LIST_REQUEST = CommandListRequest.builder().build();

    private final IOperatorConfigService configService;

    public CommandServiceImpl(IOperatorConfigService configService) {
        this.configService = configService;
    }

    @Override
    public CommandListResponse listCommands(VoidRequest aRequest) {
        List<TAgentHost> agents = configService.readConfig().getAgents();
        if (agents == null || agents.isEmpty()) {
            return CommandListResponse.builder().commands(List.of()).build();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(agents.size(), MAX_THREADS));
        try {
            List<TCommandInfo> commands = agents.stream()
                    .map(agent -> CompletableFuture.supplyAsync(() -> fetchCommands(agent), executor))
                    .toList()
                    .stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .sorted(comparing(TCommandInfo::getHost).thenComparing(CommandServiceImpl::nameKey))
                    .collect(toList());

            return CommandListResponse.builder().commands(commands).build();
        } finally {
            executor.shutdown();
        }
    }

    private List<TCommandInfo> fetchCommands(TAgentHost agent) {
        try {
            List<CommandInfoItem> items = configService.agentClient(agent.getName())
                    .listCommands(LIST_REQUEST)
                    .getCommands();

            return items.stream()
                    .map(item -> TCommandInfo.builder()
                            .host(agent.getName())
                            .name(item.getName())
                            .type(item.getType())
                            .parameters(item.getParameters())
                            .build())
                    .collect(toList());
        } catch (Exception e) {
            LOG.warn("Cannot fetch commands from agent {}", agent.getName(), e);
            return List.of(TCommandInfo.builder()
                    .host(agent.getName())
                    .error(e.getMessage())
                    .build());
        }
    }

    private static String nameKey(TCommandInfo aCommand) {
        return aCommand.getName() == null ? "" : aCommand.getName();
    }

    // ── Single-command get ──────────────────────────────────────────────────

    @Override
    public CommandDetailResponse getCommand(CommandGetRequest aRequest) {
        CommandGetResponse response;
        try {
            response = configService.agentClient(aRequest.getHost()).getCommand(
                    com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandGetRequest.builder()
                            .name(aRequest.getName())
                            .build());
        } catch (IllegalStateException e) {
            throw unreachable(aRequest.getHost(), e);
        }
        CommandDetail detail = response.getCommand();
        if (detail == null) {
            throw new ApiErrorException(CommandApiError.of(404,
                    "Command " + aRequest.getName() + " was not found on " + aRequest.getHost() + "."));
        }
        return CommandDetailResponse.builder().command(toDetail(aRequest.getHost(), detail)).build();
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Override public CommandDetailResponse createJar(CommandJarRequest r)                   { return save(r.getHost(), r.getName(), c -> c.createJar(coreJar(r))); }
    @Override public CommandDetailResponse createWar(CommandWarRequest r)                   { return save(r.getHost(), r.getName(), c -> c.createWar(coreWar(r))); }
    @Override public CommandDetailResponse createNode(CommandNodeRequest r)                 { return save(r.getHost(), r.getName(), c -> c.createNode(coreNode(r))); }
    @Override public CommandDetailResponse createSaveArtifact(CommandSaveArtifactRequest r) { return save(r.getHost(), r.getName(), c -> c.createSaveArtifact(coreSaveArtifact(r))); }
    @Override public CommandDetailResponse createZipArchive(CommandZipArchiveRequest r)     { return save(r.getHost(), r.getName(), c -> c.createZipArchive(coreZipArchive(r))); }
    @Override public CommandDetailResponse createZipDirs(CommandZipDirsRequest r)           { return save(r.getHost(), r.getName(), c -> c.createZipDirs(coreZipDirs(r))); }
    @Override public CommandDetailResponse createFetchUrl(CommandFetchUrlRequest r)         { return save(r.getHost(), r.getName(), c -> c.createFetchUrl(coreFetchUrl(r))); }
    @Override public CommandDetailResponse createDocker(CommandDockerRequest r)             { return save(r.getHost(), r.getName(), c -> c.createDocker(coreDocker(r))); }

    // ── Update ─────────────────────────────────────────────────────────────

    @Override public CommandDetailResponse updateJar(CommandJarRequest r)                   { return save(r.getHost(), r.getName(), c -> c.updateJar(coreJar(r))); }
    @Override public CommandDetailResponse updateWar(CommandWarRequest r)                   { return save(r.getHost(), r.getName(), c -> c.updateWar(coreWar(r))); }
    @Override public CommandDetailResponse updateNode(CommandNodeRequest r)                 { return save(r.getHost(), r.getName(), c -> c.updateNode(coreNode(r))); }
    @Override public CommandDetailResponse updateSaveArtifact(CommandSaveArtifactRequest r) { return save(r.getHost(), r.getName(), c -> c.updateSaveArtifact(coreSaveArtifact(r))); }
    @Override public CommandDetailResponse updateZipArchive(CommandZipArchiveRequest r)     { return save(r.getHost(), r.getName(), c -> c.updateZipArchive(coreZipArchive(r))); }
    @Override public CommandDetailResponse updateZipDirs(CommandZipDirsRequest r)           { return save(r.getHost(), r.getName(), c -> c.updateZipDirs(coreZipDirs(r))); }
    @Override public CommandDetailResponse updateFetchUrl(CommandFetchUrlRequest r)         { return save(r.getHost(), r.getName(), c -> c.updateFetchUrl(coreFetchUrl(r))); }
    @Override public CommandDetailResponse updateDocker(CommandDockerRequest r)             { return save(r.getHost(), r.getName(), c -> c.updateDocker(coreDocker(r))); }

    // ── Shared save/mapping ──────────────────────────────────────────────────

    private CommandDetailResponse save(String aHost, String aName,
            Function<IDcAgentControlPlaneRemoteService, CommandSaveResponse> aCall) {
        CommandValidator.validateName(aName);
        CommandSaveResponse response;
        try {
            response = aCall.apply(configService.agentClient(aHost));
        } catch (IllegalStateException e) {
            throw unreachable(aHost, e);
        }
        switch (response.getStatus()) {
            case CONFLICT:
                throw new ApiErrorException(CommandApiError.of(409,
                        "A command named '" + aName + "' already exists on " + aHost + "."));
            case NOT_FOUND:
                throw new ApiErrorException(CommandApiError.of(404,
                        "Command " + aName + " was not found on " + aHost + "."));
            default:
                return CommandDetailResponse.builder().command(toDetail(aHost, response.getCommand())).build();
        }
    }

    private static ApiErrorException unreachable(String aHost, Exception aCause) {
        return new ApiErrorException(CommandApiError.of(502, "Agent " + aHost + " is unreachable."), aCause);
    }

    private static TCommandDetail toDetail(String aHost, CommandDetail aDetail) {
        if (aDetail == null) {
            return null;
        }
        return TCommandDetail.builder()
                .host(aHost)
                .name(aDetail.getName())
                .type(aDetail.getType())
                .parameters(aDetail.getParameters())
                .apiKeys(aDetail.getApiKeys())
                .build();
    }

    // ── Operator request → core request (drops host; keys/config pass through typed) ──

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandJarRequest coreJar(CommandJarRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandJarRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandWarRequest coreWar(CommandWarRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandWarRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandNodeRequest coreNode(CommandNodeRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandNodeRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandSaveArtifactRequest coreSaveArtifact(CommandSaveArtifactRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandSaveArtifactRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandZipArchiveRequest coreZipArchive(CommandZipArchiveRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandZipArchiveRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandZipDirsRequest coreZipDirs(CommandZipDirsRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandZipDirsRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandFetchUrlRequest coreFetchUrl(CommandFetchUrlRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandFetchUrlRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }

    private static com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandDockerRequest coreDocker(CommandDockerRequest r) {
        return com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandDockerRequest.builder()
                .name(r.getName()).config(r.getConfig()).apiKeys(r.getApiKeys()).build();
    }
}
