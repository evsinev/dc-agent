package com.payneteasy.dcagent.operator.service.command.impl;

import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandSaveResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.NewApiKey;
import com.payneteasy.dcagent.operator.service.command.messages.CommandZipArchiveVersionRequest;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.mini.core.error.exception.ApiErrorException;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** create/update zip-archive-version: everything reaches the agent; an INVALID config is a 400 with the agent's text. */
public class CommandServiceImplZipArchiveVersionTest {

    private static final TZipArchiveVersionConfig CONFIG = TZipArchiveVersionConfig.builder()
            .dir("/opt/app/bundles")
            .versionFile("/opt/app/bundles/current")
            .versionPattern("^v[0-9]+$")
            .maxUploadBytes("50mb")
            .maxBytes("200mb")
            .maxEntries("10k")
            .reloadUrl("http://127.0.0.1:8080/reload?version=${version}")
            .reloadHeaders(Map.of("Authorization", "Bearer t"))
            .reloadBody("{\"v\":\"${version}\"}")
            .waitTimeout("6m")
            .build();

    private static final ApiKeyOps KEYS = ApiKeyOps.builder().keep(List.of("****abcd"))
            .add(List.of(NewApiKey.builder().key("secret").owner("ci").build())).build();

    private static CommandZipArchiveVersionRequest request() {
        return CommandZipArchiveVersionRequest.builder().host("h1").name("bundle").config(CONFIG).apiKeys(KEYS).build();
    }

    private static CommandServiceImpl service(String aMethod, CommandSaveResponse aResponse, AtomicReference<Object> aSent) {
        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                CommandServiceImplZipArchiveVersionTest.class.getClassLoader(),
                new Class[]{IDcAgentControlPlaneRemoteService.class},
                (proxy, method, args) -> {
                    if (aMethod.equals(method.getName())) {
                        aSent.set(args[0]);
                        return aResponse;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IOperatorConfigService config = (IOperatorConfigService) Proxy.newProxyInstance(
                CommandServiceImplZipArchiveVersionTest.class.getClassLoader(),
                new Class[]{IOperatorConfigService.class},
                (proxy, method, args) -> {
                    if ("agentClient".equals(method.getName())) {
                        return client;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new CommandServiceImpl(config);
    }

    private static CommandSaveResponse created() {
        return CommandSaveResponse.builder().status(CommandSaveStatus.CREATED)
                .command(CommandDetail.builder().name("bundle").type(TaskType.ZIP_ARCHIVE_VERSION).apiKeys(List.of()).build()).build();
    }

    @Test
    public void create_and_update_pass_name_config_and_keys_through() {
        for (String method : new String[]{"createZipArchiveVersion", "updateZipArchiveVersion"}) {
            AtomicReference<Object> sent    = new AtomicReference<>();
            CommandServiceImpl      service = service(method, created(), sent);

            var response = method.startsWith("create") ? service.createZipArchiveVersion(request()) : service.updateZipArchiveVersion(request());

            assertThat(response.getCommand().getHost()).isEqualTo("h1");
            var core = (com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandZipArchiveVersionRequest) sent.get();
            assertThat(core.getName()).as(method).isEqualTo("bundle");
            assertThat(core.getConfig()).as(method).isEqualTo(CONFIG);
            assertThat(core.getApiKeys()).as(method).isEqualTo(KEYS);
        }
    }

    @Test
    public void an_invalid_config_is_a_400_with_the_agents_message() {
        CommandSaveResponse invalid = CommandSaveResponse.builder().status(CommandSaveStatus.INVALID)
                .message("config bundle: field waitTimeout: expected a duration like 30s or 5m").build();

        Throwable thrown = catchThrowable(() -> service("createZipArchiveVersion", invalid, new AtomicReference<>())
                .createZipArchiveVersion(request()));

        assertThat(thrown).isInstanceOfSatisfying(ApiErrorException.class, e -> {
            assertThat(e.getError().getHttpReasonCode()).isEqualTo(400);
            assertThat(e.getError().toString()).contains("field waitTimeout");
        });
    }
}
