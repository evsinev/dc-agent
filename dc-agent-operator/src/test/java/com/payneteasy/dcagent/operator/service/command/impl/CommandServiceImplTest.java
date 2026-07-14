package com.payneteasy.dcagent.operator.service.command.impl;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandGetResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandSaveResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.command.messages.CommandDetailResponse;
import com.payneteasy.dcagent.operator.service.command.messages.CommandGetRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandJarRequest;
import com.payneteasy.dcagent.operator.service.command.model.TCommandDetail;
import com.payneteasy.dcagent.operator.service.command.model.TCommandInfo;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.config.model.TOperatorConfig;
import com.payneteasy.dcagent.operator.service.services.model.StatusIndicator;
import com.payneteasy.mini.core.error.exception.ApiBadRequestErrorException;
import com.payneteasy.mini.core.error.exception.ApiErrorException;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CommandServiceImplTest {

    private static CommandJarRequest jarRequest(String aName) {
        return CommandJarRequest.builder()
                .host("sandbox-1")
                .name(aName)
                .config(TJarConfig.builder().jarFilename("/srv/app.jar").serviceName("svc").build())
                .apiKeys(ApiKeyOps.builder().keep(List.of()).add(List.of()).build())
                .build();
    }

    // A config service whose agentClient returns a control-plane client driven by the given handler.
    private static IOperatorConfigService configWith(InvocationHandler aClientHandler) {
        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                CommandServiceImplTest.class.getClassLoader(),
                new Class[] { IDcAgentControlPlaneRemoteService.class },
                aClientHandler);
        return (IOperatorConfigService) Proxy.newProxyInstance(
                CommandServiceImplTest.class.getClassLoader(),
                new Class[] { IOperatorConfigService.class },
                (proxy, method, args) -> {
                    if ("agentClient".equals(method.getName())) {
                        return client;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static InvocationHandler returns(String aMethod, Object aResult) {
        return (proxy, method, args) -> {
            if (aMethod.equals(method.getName())) {
                return aResult;
            }
            throw new UnsupportedOperationException(method.getName());
        };
    }

    @Test
    public void conflict_from_the_agent_maps_to_http_409() {
        CommandServiceImpl service = new CommandServiceImpl(
                configWith(returns("createJar", CommandSaveResponse.builder().status(CommandSaveStatus.CONFLICT).build())));
        try {
            service.createJar(jarRequest("billing"));
            fail("expected ApiErrorException");
        } catch (ApiErrorException e) {
            assertEquals(409, e.getError().getHttpReasonCode());
        }
    }

    @Test
    public void not_found_on_update_maps_to_http_404() {
        CommandServiceImpl service = new CommandServiceImpl(
                configWith(returns("updateJar", CommandSaveResponse.builder().status(CommandSaveStatus.NOT_FOUND).build())));
        try {
            service.updateJar(jarRequest("billing"));
            fail("expected ApiErrorException");
        } catch (ApiErrorException e) {
            assertEquals(404, e.getError().getHttpReasonCode());
        }
    }

    @Test
    public void unreachable_agent_maps_to_http_502() {
        CommandServiceImpl service = new CommandServiceImpl(configWith((proxy, method, args) -> {
            throw new IllegalStateException("Cannot send task to agent");
        }));
        try {
            service.createJar(jarRequest("billing"));
            fail("expected ApiErrorException");
        } catch (ApiErrorException e) {
            assertEquals(502, e.getError().getHttpReasonCode());
        }
    }

    @Test
    public void invalid_name_maps_to_http_400_with_field_errors() {
        CommandServiceImpl service = new CommandServiceImpl(configWith(returns("noop", null)));
        try {
            service.createJar(jarRequest("bad name"));
            fail("expected ApiBadRequestErrorException");
        } catch (ApiBadRequestErrorException e) {
            assertEquals(400, e.getError().getHttpReasonCode());
            assertEquals("name", e.getBadRequestError().getInvalidParams().get(0).getName());
        }
    }

    @Test
    public void created_command_is_returned_with_the_host() {
        CommandDetail detail = CommandDetail.builder()
                .name("billing")
                .type(TaskType.JAR)
                .parameters(Map.of("jarFilename", "/srv/app.jar"))
                .apiKeys(List.of())
                .build();
        CommandServiceImpl service = new CommandServiceImpl(
                configWith(returns("createJar", CommandSaveResponse.builder().status(CommandSaveStatus.CREATED).command(detail).build())));

        CommandDetailResponse response = service.createJar(jarRequest("billing"));
        assertEquals("sandbox-1", response.getCommand().getHost());
        assertEquals("billing", response.getCommand().getName());
        assertEquals(TaskType.JAR, response.getCommand().getType());
    }

    // ── Server-side service-state enrichment (join command → service on serviceName) ──────────

    @Test
    public void command_list_carries_service_state_joined_on_service_name() {
        TAgentHost agent = TAgentHost.builder().name("sandbox-1").url("http://agent").build();
        CommandInfoItem item = CommandInfoItem.builder()
                .name("billing").type(TaskType.JAR)
                .parameters(Map.of("serviceName", "billing"))
                .build();
        InvocationHandler client = routing(Map.of(
                "listCommands", com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandListResponse.builder()
                        .commands(List.of(item)).build(),
                "listServices", servicesResponse(serviceUp("billing"))));
        CommandServiceImpl service = new CommandServiceImpl(fleetWith(List.of(agent), client));

        List<TCommandInfo> commands = service.listCommands(null).getCommands();
        assertEquals(1, commands.size());
        assertEquals("Running", commands.get(0).getServiceStatusName());
        assertEquals(StatusIndicator.SUCCESS, commands.get(0).getServiceStatusIndicator());
    }

    @Test
    public void command_list_without_matching_service_has_no_state() {
        TAgentHost agent = TAgentHost.builder().name("sandbox-1").url("http://agent").build();
        CommandInfoItem item = CommandInfoItem.builder()
                .name("logs-mirror").type(TaskType.ZIP_DIRS)
                .parameters(Map.of("dir", "/var/log"))   // no serviceName
                .build();
        InvocationHandler client = routing(Map.of(
                "listCommands", com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandListResponse.builder()
                        .commands(List.of(item)).build(),
                "listServices", servicesResponse(serviceUp("billing"))));
        CommandServiceImpl service = new CommandServiceImpl(fleetWith(List.of(agent), client));

        TCommandInfo command = service.listCommands(null).getCommands().get(0);
        assertEquals(null, command.getServiceStatusName());
        assertEquals(null, command.getServiceStatusIndicator());
    }

    @Test
    public void command_detail_carries_service_state_joined_on_service_name() {
        TAgentHost agent = TAgentHost.builder().name("sandbox-1").url("http://agent").build();
        CommandDetail detail = CommandDetail.builder()
                .name("billing").type(TaskType.JAR)
                .parameters(Map.of("serviceName", "billing"))
                .apiKeys(List.of())
                .build();
        InvocationHandler client = routing(Map.of(
                "getCommand", CommandGetResponse.builder().command(detail).build(),
                "listServices", servicesResponse(serviceUp("billing"))));
        CommandServiceImpl service = new CommandServiceImpl(fleetWith(List.of(agent), client));

        TCommandDetail result = service
                .getCommand(CommandGetRequest.builder().host("sandbox-1").name("billing").build())
                .getCommand();
        assertEquals("Running", result.getServiceStatusName());
        assertEquals(StatusIndicator.SUCCESS, result.getServiceStatusIndicator());
    }

    // A control-plane client whose result is chosen by method name.
    private static InvocationHandler routing(Map<String, ?> aByMethod) {
        return (proxy, method, args) -> {
            if (aByMethod.containsKey(method.getName())) {
                return aByMethod.get(method.getName());
            }
            throw new UnsupportedOperationException(method.getName());
        };
    }

    // A config service backed by a fixed agent list, using one control-plane client for every agent.
    private static IOperatorConfigService fleetWith(List<TAgentHost> aAgents, InvocationHandler aClientHandler) {
        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                CommandServiceImplTest.class.getClassLoader(),
                new Class[] { IDcAgentControlPlaneRemoteService.class },
                aClientHandler);
        return (IOperatorConfigService) Proxy.newProxyInstance(
                CommandServiceImplTest.class.getClassLoader(),
                new Class[] { IOperatorConfigService.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "agentClient"   -> client;
                    case "readConfig"    -> TOperatorConfig.builder().agents(aAgents).build();
                    case "findAgentHost" -> aAgents.stream().filter(a -> a.getName().equals(args[0])).findFirst();
                    default              -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ServiceListResponse servicesResponse(ServiceInfoItem... aItems) {
        return ServiceListResponse.builder().services(List.of(aItems)).build();
    }

    private static ServiceInfoItem serviceUp(String aName) {
        return ServiceInfoItem.builder()
                .name(aName)
                .status(ServiceStatus.builder().state(ServiceStateType.UP).build())
                .build();
    }
}
