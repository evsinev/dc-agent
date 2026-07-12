package com.payneteasy.dcagent.operator.service.command.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandInfoItem;
import com.payneteasy.dcagent.operator.service.command.ICommandService;
import com.payneteasy.dcagent.operator.service.command.messages.CommandListResponse;
import com.payneteasy.dcagent.operator.service.command.model.TCommandInfo;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

/**
 * Lists the commands (deployment endpoints) configured on each agent by fanning out to every host
 * and calling the agent's control-plane {@code command/list}. Docker is excluded on the agent side.
 * A host that cannot be reached contributes a single row carrying its error.
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
}
