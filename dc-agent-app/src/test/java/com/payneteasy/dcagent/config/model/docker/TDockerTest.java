package com.payneteasy.dcagent.config.model.docker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.dcagent.yaml2json.Yaml2GsonConverter;
import org.junit.Test;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;

import java.util.Optional;

public class TDockerTest {

    @Test
    public void serialize_yaml() {

        String yaml = "version: 0.0.1\n" +
                "\n" +
                "boundVariables:\n" +
                "  - name: DOCKER_TAG\n" +
                "    value: 8-alpine3.16-jre\n" +
                "\n" +
                "  - name: APP_VERSION\n" +
                "    value: 1.0-3\n" +
                "\n" +
                "name: dc-agent\n" +
                "\n" +
                "hostBaseDir:         /tmp/dc-agent\n" +
                "containerWorkingDir: /opt/dc-agent\n" +
                "\n" +
                "image:\n" +
                "  name: \"amazoncorretto:{{ DOCKER_TAG }}\"\n" +
                "\n" +
                "volumes:\n" +
                "  - dirConfig:\n" +
                "      configPath:  ./config\n" +
                "      source:      /tmp/dc-agent/config\n" +
                "      destination: /opt/dc-agent/config\n" +
                "      readonly:    true\n" +
                "\n" +
                "  - fileFetchUrl:\n" +
                "      url:         https://github.com/evsinev/dc-agent/releases/download/{{ APP_VERSION }}/dc-agent-{{ APP_VERSION }}.jar\n" +
                "      version:     \"{{ APP_VERSION }}\"\n" +
                "      destination: /opt/dc-agent/versions/{{ APP_VERSION }}.jar\n" +
                "      source:      /tmp/dc-agent/versions/{{ APP_VERSION }}.jar\n" +
                "\n" +
                "\n" +
                "env:\n" +
                "  - name: WEB_SERVER_PORT\n" +
                "    value: \"8051\"\n" +
                "\n" +
                "  - name: WEB_SERVER_CONTEXT\n" +
                "    value: \"/dc-agent\"\n" +
                "\n" +
                "  - name: CONFIG_DIR\n" +
                "    value: /opt/dc-agent/config\n" +
                "\n" +
                "\n" +
                "args:\n" +
                "  - java\n" +
                "  - -jar\n" +
                "  - /opt/dc-agent/versions/{{ APP_VERSION }}.jar\n" +
                "\n";

        Compose compose = new Compose(LoadSettings.builder().build());
        Optional<Node> nodeOption = compose.composeString(yaml);
        Node node = nodeOption.get();

        Gson   gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(new Yaml2GsonConverter().convertToJson((MappingNode) node));
        System.out.println("json = " + json);

        TDocker service = gson.fromJson(json, TDocker.class);
        System.out.println("service = " + service);
        System.out.println("gson.toJson(service) = " + gson.toJson(service));
    }
}