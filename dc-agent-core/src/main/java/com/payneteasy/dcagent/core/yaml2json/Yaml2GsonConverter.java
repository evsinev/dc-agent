package com.payneteasy.dcagent.core.yaml2json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.nodes.*;

import java.util.ArrayList;
import java.util.List;

public class Yaml2GsonConverter {

    private static final Logger LOG = LoggerFactory.getLogger( Yaml2GsonConverter.class );

    /**
     * Strict: a key repeated on one level or a non-scalar key is an error instead of "the last
     * one wins" / skipped — otherwise a typo in the first of two sections vanishes before the keys
     * are checked.
     */
    private final boolean strict;

    public Yaml2GsonConverter() {
        this(false);
    }

    public Yaml2GsonConverter(boolean aStrict) {
        strict = aStrict;
    }

    public JsonObject convertToJson(MappingNode aNode) {
        List<String> errors = new ArrayList<>();
        JsonObject   object = convertToJson(new JsonObject(), aNode, "", errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n  - ", errors));
        }
        return object;
    }

    private JsonObject convertToJson(JsonObject aObject, MappingNode aNode, String aPath, List<String> aErrors) {
        for (NodeTuple tuple : aNode.getValue()) {

            Node keyNode = tuple.getKeyNode();
            if(keyNode.getNodeType() != NodeType.SCALAR) {
                if (strict) {
                    aErrors.add((aPath.isEmpty() ? "top level" : aPath) + ": a key must be a plain name, got " + keyNode.getNodeType());
                }
                LOG.warn("Node {} is not SCALAR", keyNode);
                continue;
            }

            String name      = ((ScalarNode) keyNode).getValue();
            Node   valueNode = tuple.getValueNode();
            String path      = aPath.isEmpty() ? name : aPath + "." + name;

            if (strict && aObject.has(name)) {
                aErrors.add(path + ": duplicate key");
            }
            aObject.add(name, convertAny(valueNode, path, aErrors));

        }
        return aObject;
    }

    private JsonArray convertSequence(Node aNode, String aPath, List<String> aErrors) {
        SequenceNode sequenceNode = (SequenceNode) aNode;
        JsonArray array = new JsonArray();
        for (Node node : sequenceNode.getValue()) {
            array.add(convertAny(node, aPath + "[" + array.size() + "]", aErrors));
        }
        return array;
    }

    private JsonObject convertMapping(Node aNode, String aPath, List<String> aErrors) {
        MappingNode mappingNode = (MappingNode) aNode;
        return convertToJson(new JsonObject(), mappingNode, aPath, aErrors);
    }

    private JsonPrimitive convertScalar(Node aNode) {
        ScalarNode scalarNode = (ScalarNode) aNode;
        return new JsonPrimitive(scalarNode.getValue());
    }

    private JsonElement convertAny(Node aValueNode, String aPath, List<String> aErrors) {
        switch (aValueNode.getNodeType()) {
            case SCALAR:
                return convertScalar(aValueNode);

            case MAPPING:
                return convertMapping(aValueNode, aPath, aErrors);

            case SEQUENCE:
                return convertSequence(aValueNode, aPath, aErrors);

            case ANCHOR:
                throw new IllegalStateException("Anchor node type " + aValueNode);

            default:
                throw new IllegalStateException("Unknown node type " + aValueNode.getNodeType());
        }
    }
}
