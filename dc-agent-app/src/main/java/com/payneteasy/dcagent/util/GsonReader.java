package com.payneteasy.dcagent.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class GsonReader {

    private final Gson gson;

    public GsonReader(Gson gson) {
        this.gson = gson;
    }

    public <T> T loadFile(File aFile, Class<T> aType) {
        try {
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(aFile), UTF_8)) {
                return gson.fromJson(in, aType);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load file " + aFile.getAbsolutePath() + " for type " + aType, e);
        }
    }

    public JsonObject loadJsonObject(File aFile) {
        try {
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(aFile), UTF_8)) {
                return (JsonObject) JsonParser.parseReader(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load file " + aFile.getAbsolutePath(), e);
        }
    }

}
