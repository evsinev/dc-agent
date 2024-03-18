package com.payneteasy.dcagent.core.util;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Base64;

public class GsonBase64TypeAdapter extends TypeAdapter<byte[]> {

    public static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    public static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    @Override
    public void write(JsonWriter out, byte[] value) throws IOException {
        if(value == null) {
            out.nullValue();
        } else {
            out.value(BASE64_ENCODER.encodeToString(value));
        }
    }

    @Override
    public byte[] read(JsonReader in) throws IOException {
        String value = in.nextString();
        if(value == null || value.isEmpty()) {
            return null;
        }
        return BASE64_DECODER.decode(value);
    }
}
