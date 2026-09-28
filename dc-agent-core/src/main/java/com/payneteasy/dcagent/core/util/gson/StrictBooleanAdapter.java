package com.payneteasy.dcagent.core.util.gson;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.fieldPath;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.invalid;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.readScalar;

/**
 * Accepts only the YAML 1.2 core booleans: {@code true|True|TRUE|false|False|FALSE}.
 * Plain Gson uses {@code Boolean.parseBoolean},
 * which silently turns "yes" or a typo into {@code false} — for a security flag that means
 * the protection is quietly off.
 */
public class StrictBooleanAdapter extends TypeAdapter<Boolean> {

    private static final String EXPECTED = "true or false";

    @Override
    public void write(JsonWriter aWriter, Boolean aValue) throws IOException {
        aWriter.value(aValue);
    }

    @Override
    public Boolean read(JsonReader aReader) throws IOException {
        String path = fieldPath(aReader);
        String text = readScalar(aReader, path, EXPECTED);
        switch (text) {
            case "true":
            case "True":
            case "TRUE":
                return Boolean.TRUE;
            case "false":
            case "False":
            case "FALSE":
                return Boolean.FALSE;
            default:
                throw invalid(path, EXPECTED, text);
        }
    }
}
