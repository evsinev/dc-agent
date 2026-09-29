package com.payneteasy.dcagent.core.yaml2json;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class YamlParser {

    private final Yaml2GsonConverter yaml2GsonConverter = new Yaml2GsonConverter();
    private final Yaml2GsonConverter strictConverter    = new Yaml2GsonConverter(true);
    private final Compose            compose;
    private final Gson               gson;

    public YamlParser() {
        compose = new Compose(LoadSettings.builder().build());
        gson    = new Gson();
    }

    public <T> T parseFile(File aFile, Class<T> aClass) {
        try {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(aFile), UTF_8)) {
                Node       node   = compose.composeReader(reader).orElseThrow(() -> new IllegalStateException("No any node"));
                JsonObject object = yaml2GsonConverter.convertToJson((MappingNode) node);
                return gson.fromJson(object, aClass);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse file " + aFile.getAbsolutePath(), e);
        }

    }

    public <T> T parseText(String aText, Class<T> aClass) {
        Node       node   = compose.composeString(aText).orElseThrow(() -> new IllegalStateException("No any node"));
        JsonObject object = yaml2GsonConverter.convertToJson((MappingNode) node);
        return gson.fromJson(object, aClass);
    }

    /**
     * Like {@link #parseText} but rejects keys the model does not have and keys repeated on one
     * level — every problem with its path, in one message.
     *
     * @param aSourceName the file name for the message, e.g. {@code dc-docker.yml}
     */
    public <T> T parseTextStrict(String aText, Class<T> aClass, String aSourceName) {
        Node       node = compose.composeString(aText).orElseThrow(() -> new IllegalStateException("No any node"));
        JsonObject object;
        try {
            object = strictConverter.convertToJson((MappingNode) node);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(aSourceName + ":\n  - " + e.getMessage(), e);
        }
        List<String> unknown = StrictKeys.unknownKeys(object, aClass);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(aSourceName + ": unknown keys, fix or remove them:\n  - " + String.join("\n  - ", unknown));
        }
        return gson.fromJson(object, aClass);
    }
}
