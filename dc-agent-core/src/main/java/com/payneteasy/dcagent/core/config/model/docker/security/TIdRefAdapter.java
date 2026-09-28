package com.payneteasy.dcagent.core.config.model.docker.security;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.payneteasy.dcagent.core.util.gson.StrictIdAdapter;

import java.io.IOException;

import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.fieldPath;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.invalid;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.readScalar;

/**
 * {@code "1001"} → id, {@code "runAsUser"} / {@code "runAsGroup"} → ref, anything else fails
 * with the field path. Writes back the same YAML form.
 */
public class TIdRefAdapter extends TypeAdapter<TIdRef> {

    private static final String EXPECTED = StrictIdAdapter.EXPECTED + ", runAsUser or runAsGroup";

    @Override
    public void write(JsonWriter aWriter, TIdRef aValue) throws IOException {
        if (aValue == null) {
            aWriter.nullValue();
        } else if (aValue.getRef() != null) {
            aWriter.value(aValue.getRef().yamlName());
        } else {
            aWriter.value(aValue.getId());
        }
    }

    @Override
    public TIdRef read(JsonReader aReader) throws IOException {
        String path = fieldPath(aReader);
        String text = readScalar(aReader, path, EXPECTED);

        Integer id = StrictIdAdapter.parseId(text);
        if (id != null) {
            return TIdRef.builder().id(id).build();
        }

        TIdSource ref = TIdSource.fromYaml(text);
        if (ref != null) {
            return TIdRef.builder().ref(ref).build();
        }

        throw invalid(path, EXPECTED, text);
    }
}
