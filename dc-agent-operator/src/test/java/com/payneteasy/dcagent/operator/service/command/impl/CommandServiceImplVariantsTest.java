package com.payneteasy.dcagent.operator.service.command.impl;

import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandSaveResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import com.payneteasy.dcagent.operator.service.command.messages.CommandDetailResponse;
import com.payneteasy.dcagent.operator.service.command.messages.CommandDockerRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandFetchUrlRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandJarRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandNodeRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandSaveArtifactRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandWarRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandZipArchiveRequest;
import com.payneteasy.dcagent.operator.service.command.messages.CommandZipDirsRequest;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers every create and update variant of CommandServiceImpl (each delegates to save() through a distinct
 * core-request mapper). A proxy config-service returns a CREATED response for the matching client method.
 */
public class CommandServiceImplVariantsTest {

    private static final String HOST = "sandbox-1";
    private static final String NAME = "billing";
    private static final ApiKeyOps KEYS = ApiKeyOps.builder().keep(List.of()).add(List.of()).build();

    private static CommandServiceImpl serviceExpecting(String clientMethod) {
        CommandDetail detail = CommandDetail.builder().name(NAME).type(TaskType.JAR).apiKeys(List.of()).build();
        CommandSaveResponse response = CommandSaveResponse.builder()
                .status(CommandSaveStatus.CREATED).command(detail).build();

        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                CommandServiceImplVariantsTest.class.getClassLoader(),
                new Class[]{IDcAgentControlPlaneRemoteService.class},
                (proxy, method, args) -> {
                    if (clientMethod.equals(method.getName())) {
                        return response;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IOperatorConfigService config = (IOperatorConfigService) Proxy.newProxyInstance(
                CommandServiceImplVariantsTest.class.getClassLoader(),
                new Class[]{IOperatorConfigService.class},
                (proxy, method, args) -> {
                    if ("agentClient".equals(method.getName())) {
                        return client;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new CommandServiceImpl(config);
    }

    private void assertReturnsHost(CommandDetailResponse response) {
        assertThat(response.getCommand().getHost()).isEqualTo(HOST);
    }

    private CommandJarRequest jar() {
        return CommandJarRequest.builder().host(HOST).name(NAME).config(TJarConfig.builder().build()).apiKeys(KEYS).build();
    }

    private CommandWarRequest war() {
        return CommandWarRequest.builder().host(HOST).name(NAME).config(TJarConfig.builder().build()).apiKeys(KEYS).build();
    }

    private CommandNodeRequest node() {
        return CommandNodeRequest.builder().host(HOST).name(NAME).config(TJarConfig.builder().build()).apiKeys(KEYS).build();
    }

    private CommandSaveArtifactRequest saveArtifact() {
        return CommandSaveArtifactRequest.builder().host(HOST).name(NAME).config(TSaveArtifactConfig.builder().build()).apiKeys(KEYS).build();
    }

    private CommandZipArchiveRequest zipArchive() {
        return CommandZipArchiveRequest.builder().host(HOST).name(NAME).config(TZipArchiveConfig.builder().build()).apiKeys(KEYS).build();
    }

    private CommandZipDirsRequest zipDirs() {
        return CommandZipDirsRequest.builder().host(HOST).name(NAME).config(TZipDirsConfig.builder().build()).apiKeys(KEYS).build();
    }

    private CommandFetchUrlRequest fetchUrl() {
        return CommandFetchUrlRequest.builder().host(HOST).name(NAME).config(TFetchUrlConfig.builder().build()).apiKeys(KEYS).build();
    }

    private CommandDockerRequest docker() {
        return CommandDockerRequest.builder().host(HOST).name(NAME).config(TDockerConfig.builder().build()).apiKeys(KEYS).build();
    }

    @Test public void create_jar()           { assertReturnsHost(serviceExpecting("createJar").createJar(jar())); }
    @Test public void create_war()           { assertReturnsHost(serviceExpecting("createWar").createWar(war())); }
    @Test public void create_node()          { assertReturnsHost(serviceExpecting("createNode").createNode(node())); }
    @Test public void create_save_artifact() { assertReturnsHost(serviceExpecting("createSaveArtifact").createSaveArtifact(saveArtifact())); }
    @Test public void create_zip_archive()   { assertReturnsHost(serviceExpecting("createZipArchive").createZipArchive(zipArchive())); }
    @Test public void create_zip_dirs()      { assertReturnsHost(serviceExpecting("createZipDirs").createZipDirs(zipDirs())); }
    @Test public void create_fetch_url()     { assertReturnsHost(serviceExpecting("createFetchUrl").createFetchUrl(fetchUrl())); }
    @Test public void create_docker()        { assertReturnsHost(serviceExpecting("createDocker").createDocker(docker())); }

    @Test public void update_jar()           { assertReturnsHost(serviceExpecting("updateJar").updateJar(jar())); }
    @Test public void update_war()           { assertReturnsHost(serviceExpecting("updateWar").updateWar(war())); }
    @Test public void update_node()          { assertReturnsHost(serviceExpecting("updateNode").updateNode(node())); }
    @Test public void update_save_artifact() { assertReturnsHost(serviceExpecting("updateSaveArtifact").updateSaveArtifact(saveArtifact())); }
    @Test public void update_zip_archive()   { assertReturnsHost(serviceExpecting("updateZipArchive").updateZipArchive(zipArchive())); }
    @Test public void update_zip_dirs()      { assertReturnsHost(serviceExpecting("updateZipDirs").updateZipDirs(zipDirs())); }
    @Test public void update_fetch_url()     { assertReturnsHost(serviceExpecting("updateFetchUrl").updateFetchUrl(fetchUrl())); }
    @Test public void update_docker()        { assertReturnsHost(serviceExpecting("updateDocker").updateDocker(docker())); }
}
