package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.core.remote.agent.appstatus.AgentAppStatusClientFactory;
import com.payneteasy.dcagent.core.remote.agent.appstatus.TAgentAppStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.SystemInfoRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.SystemInfoResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.agent.IAgentService;
import com.payneteasy.dcagent.operator.service.agent.messages.AgentListResponse;
import com.payneteasy.dcagent.operator.service.agent.model.TAgentInfo;
import com.payneteasy.dcagent.operator.service.agent.model.TAgentServiceBrief;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.services.impl.HostServiceItemMapper;
import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

public class AgentServiceImpl implements IAgentService {

    private static final Logger             LOG          = LoggerFactory.getLogger(AgentServiceImpl.class);
    private static final int                MAX_THREADS         = 8;
    private static final ServiceListRequest LIST_REQUEST        = ServiceListRequest.builder().build();
    private static final SystemInfoRequest  SYSTEM_INFO_REQUEST = SystemInfoRequest.builder().build();

    private final IOperatorConfigService      configService;
    private final AgentAppStatusClientFactory appStatusClientFactory;

    public AgentServiceImpl(IOperatorConfigService configService, AgentAppStatusClientFactory appStatusClientFactory) {
        this.configService          = configService;
        this.appStatusClientFactory = appStatusClientFactory;
    }

    @Override
    public AgentListResponse listAgents(VoidRequest aRequest) {
        List<TAgentHost> agents = configService.readConfig().getAgents();
        if (agents == null || agents.isEmpty()) {
            return AgentListResponse.builder().agents(List.of()).build();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(agents.size(), MAX_THREADS));
        try {
            List<TAgentInfo> result = agents.stream()
                    .map(agent -> CompletableFuture.supplyAsync(() -> toAgentInfo(agent), executor))
                    .toList()
                    .stream()
                    .map(CompletableFuture::join)
                    .sorted(comparing(TAgentInfo::getName))
                    .collect(toList());

            return AgentListResponse.builder().agents(result).build();
        } finally {
            executor.shutdown();
        }
    }

    private TAgentInfo toAgentInfo(TAgentHost agent) {
        TAgentInfo.TAgentInfoBuilder builder = TAgentInfo.builder()
                .name(agent.getName())
                .url(agent.getUrl());

        fetchAppStatus(agent, builder);
        fetchServices(agent, builder);
        fetchMetrics(agent, builder);

        return builder.build();
    }

    private void fetchMetrics(TAgentHost agent, TAgentInfo.TAgentInfoBuilder builder) {
        try {
            SystemInfoResponse response = configService.agentClient(agent.getName()).getSystemInfo(SYSTEM_INFO_REQUEST);
            builder.metrics(AgentMetricsMapper.toMetrics(response.getSystemInfo()));
        } catch (Exception e) {
            LOG.warn("Cannot fetch metrics from agent {}", agent.getName(), e);
            builder.metricsError(e.getMessage());
        }
    }

    private void fetchAppStatus(TAgentHost agent, TAgentInfo.TAgentInfoBuilder builder) {
        try {
            TAgentAppStatus status = appStatusClientFactory
                    .createClient(agent.getUrl(), agent.getAppStatusToken())
                    .fetch();

            builder.reachable(true)
                    .appInstanceName(status.getAppInstanceName())
                    .appVersion(status.getAppVersion())
                    .hostname(status.getHostname())
                    .port(status.getPort())
                    .uptimeMs(status.getUptimeMs())
                    .uptimeFormatted(formatUptime(status.getUptimeMs()))
                    .responseEpoch(status.getResponseEpoch())
                    .responseId(status.getResponseId());
        } catch (Exception e) {
            LOG.warn("Cannot fetch app-status from agent {}", agent.getName(), e);
            builder.reachable(false).error(e.getMessage());
        }
    }

    private void fetchServices(TAgentHost agent, TAgentInfo.TAgentInfoBuilder builder) {
        try {
            ServiceListResponse   response = configService.agentClient(agent.getName()).listServices(LIST_REQUEST);
            List<ServiceInfoItem> services = response.getServices();

            builder.servicesTotal(services.size())
                    .servicesUp((int) services.stream().filter(AgentServiceImpl::isRunning).count())
                    .services(services.stream().map(service -> toBrief(agent, service)).collect(toList()));
        } catch (Exception e) {
            LOG.warn("Cannot fetch services from agent {}", agent.getName(), e);
            builder.servicesError(e.getMessage());
        }
    }

    private static boolean isRunning(ServiceInfoItem service) {
        ServiceStatus    status = service.getStatus();
        ServiceStateType state  = status == null ? null : status.getState();
        return state != null && state.isRunning();
    }

    private static TAgentServiceBrief toBrief(TAgentHost agent, ServiceInfoItem service) {
        HostServiceItem item = HostServiceItemMapper.toHostService(agent, service);
        return TAgentServiceBrief.builder()
                .serviceName(service.getName())
                .statusName(item.getStatusName())
                .statusIndicator(item.getStatusIndicator())
                .build();
    }

    private static String formatUptime(long aUptimeMs) {
        Duration duration = Duration.ofMillis(aUptimeMs);
        long     days     = duration.toDays();
        long     hours    = duration.toHoursPart();
        long     minutes  = duration.toMinutesPart();
        long     seconds  = duration.toSecondsPart();

        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
