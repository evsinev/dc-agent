package com.payneteasy.dcagent.core.util.gson;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;

/**
 * Helpers for strict scalar adapters. dc-docker.yml reaches Gson through Yaml2GsonConverter,
 * which turns every YAML scalar into a JSON string, so plain Gson would accept "yes" as
 * {@code false} for a Boolean. Strict adapters read the raw text and fail with the field path.
 */
public final class StrictGsonReader {

    private StrictGsonReader() {
    }

    public static String readScalar(JsonReader aReader, String aPath, String aExpected) throws IOException {
        JsonToken token = aReader.peek();
        switch (token) {
            case STRING:
            case NUMBER:
                return aReader.nextString();
            case BOOLEAN:
                return Boolean.toString(aReader.nextBoolean());
            default:
                aReader.skipValue();
                throw new JsonParseException(aPath + ": expected " + aExpected + ", got " + token);
        }
    }

    public static JsonParseException invalid(String aPath, String aExpected, String aValue) {
        return new JsonParseException(aPath + ": expected " + aExpected + ", got '" + aValue + "'");
    }

    /** Path of the value about to be read, e.g. {@code securityContext.runAsUser}. Call before reading. */
    public static String fieldPath(JsonReader aReader) {
        String path = aReader.getPath();
        return path.startsWith("$.") ? path.substring(2) : path;
    }
}
