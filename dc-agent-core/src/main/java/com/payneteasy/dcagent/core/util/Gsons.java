package com.payneteasy.dcagent.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Gsons {

    public static final Gson PRETTY_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

}
