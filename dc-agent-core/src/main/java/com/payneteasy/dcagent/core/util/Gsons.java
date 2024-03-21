package com.payneteasy.dcagent.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Gsons {

    public static final Gson PRETTY_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(byte[].class, new GsonBase64TypeAdapter())
            .setPrettyPrinting()
            .create();

}
