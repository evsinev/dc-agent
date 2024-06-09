package com.payneteasy.dcagent.core.util.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Date;

public class Gsons {

    public static final Gson PRETTY_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(byte[].class, new GsonBase64TypeAdapter())
            .registerTypeAdapter(Date.class, new DateLongFormatTypeAdapter())
            .setPrettyPrinting()
            .create();

}
