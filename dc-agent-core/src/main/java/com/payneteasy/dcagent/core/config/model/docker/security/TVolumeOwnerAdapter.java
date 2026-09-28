package com.payneteasy.dcagent.core.config.model.docker.security;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

import static com.payneteasy.dcagent.core.config.model.docker.security.TIdSource.RUN_AS_GROUP;
import static com.payneteasy.dcagent.core.config.model.docker.security.TIdSource.RUN_AS_USER;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.fieldPath;

/**
 * {@code owner: runAs} → {@code {user: runAsUser, group: runAsGroup}}; {@code owner: {user, group}}
 * → fields one by one. The shorthand exists only while parsing: everything after this adapter
 * sees the object form. Unknown keys fail — this structure is new, nothing depends on leniency.
 */
public class TVolumeOwnerAdapter extends TypeAdapter<TVolumeOwner> {

    private static final String SHORTHAND = "runAs";
    private static final String EXPECTED  = "'" + SHORTHAND + "' or { user, group }";

    private final TIdRefAdapter idRefAdapter = new TIdRefAdapter();

    @Override
    public void write(JsonWriter aWriter, TVolumeOwner aValue) throws IOException {
        if (aValue == null) {
            aWriter.nullValue();
            return;
        }
        aWriter.beginObject();
        if (aValue.getUser() != null) {
            aWriter.name("user");
            idRefAdapter.write(aWriter, aValue.getUser());
        }
        if (aValue.getGroup() != null) {
            aWriter.name("group");
            idRefAdapter.write(aWriter, aValue.getGroup());
        }
        aWriter.endObject();
    }

    @Override
    public TVolumeOwner read(JsonReader aReader) throws IOException {
        String    path  = fieldPath(aReader);
        JsonToken token = aReader.peek();

        if (token == JsonToken.STRING) {
            String text = aReader.nextString();
            if (!SHORTHAND.equals(text)) {
                throw new JsonParseException(path + ": expected " + EXPECTED + ", got '" + text + "'");
            }
            return TVolumeOwner.builder()
                    .user  ( TIdRef.builder().ref(RUN_AS_USER ).build() )
                    .group ( TIdRef.builder().ref(RUN_AS_GROUP).build() )
                    .build();
        }

        if (token != JsonToken.BEGIN_OBJECT) {
            aReader.skipValue();
            throw new JsonParseException(path + ": expected " + EXPECTED + ", got " + token);
        }

        TIdRef user  = null;
        TIdRef group = null;
        aReader.beginObject();
        while (aReader.hasNext()) {
            String name = aReader.nextName();
            switch (name) {
                case "user":
                    user = idRefAdapter.read(aReader);
                    break;
                case "group":
                    group = idRefAdapter.read(aReader);
                    break;
                default:
                    throw new JsonParseException(path + ": unknown key '" + name + "', expected user or group");
            }
        }
        aReader.endObject();

        if (user == null && group == null) {
            throw new JsonParseException(path + ": expected at least one of user, group");
        }

        return TVolumeOwner.builder()
                .user  ( user  )
                .group ( group )
                .build();
    }
}
