package com.payneteasy.dcagent.core.config.model.docker.volumes;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.fieldPath;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.invalid;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.readScalar;

/**
 * Plain Gson turns an unknown enum value into {@code null}, which would silently mean OVERLAY for
 * a typo in {@code sync}. This adapter fails with the field path instead. Case-sensitive.
 */
public class TDirSyncAdapter extends TypeAdapter<TDirSync> {

    private static final String EXPECTED = "OVERLAY or MIRROR";

    @Override
    public void write(JsonWriter aWriter, TDirSync aValue) throws IOException {
        if (aValue == null) {
            aWriter.nullValue();
        } else {
            aWriter.value(aValue.name());
        }
    }

    @Override
    public TDirSync read(JsonReader aReader) throws IOException {
        String path = fieldPath(aReader);
        String text = readScalar(aReader, path, EXPECTED);
        for (TDirSync sync : TDirSync.values()) {
            if (sync.name().equals(text)) {
                return sync;
            }
        }
        throw invalid(path, EXPECTED, text);
    }
}
