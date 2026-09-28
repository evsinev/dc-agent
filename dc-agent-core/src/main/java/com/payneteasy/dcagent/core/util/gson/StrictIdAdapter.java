package com.payneteasy.dcagent.core.util.gson;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.fieldPath;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.invalid;
import static com.payneteasy.dcagent.core.util.gson.StrictGsonReader.readScalar;

/**
 * Numeric UID/GID: digits only, 0..2147483647. Docker (moby) parses --user ids into int32,
 * so anything larger would pass here and fail only when the container starts. Names are
 * rejected: {@code docker --user name} resolves the name in the image, not on the host.
 */
public class StrictIdAdapter extends TypeAdapter<Integer> {

    public static final String EXPECTED = "a numeric id 0..2147483647";

    @Override
    public void write(JsonWriter aWriter, Integer aValue) throws IOException {
        aWriter.value(aValue);
    }

    @Override
    public Integer read(JsonReader aReader) throws IOException {
        String  path = fieldPath(aReader);
        String  text = readScalar(aReader, path, EXPECTED);
        Integer id   = parseId(text);
        if (id == null) {
            throw invalid(path, EXPECTED, text);
        }
        return id;
    }

    /**
     * @return the id, or {@code null} when the text is not an id in range
     */
    public static Integer parseId(String aText) {
        if (aText.isEmpty()) {
            return null;
        }
        for (int i = 0; i < aText.length(); i++) {
            char c = aText.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        String digits = stripLeadingZeros(aText);
        if (digits.length() > 10) {
            return null;
        }
        long value = Long.parseLong(digits);
        return value > Integer.MAX_VALUE ? null : (int) value;
    }

    private static String stripLeadingZeros(String aDigits) {
        int start = 0;
        while (start < aDigits.length() - 1 && aDigits.charAt(start) == '0') {
            start++;
        }
        return aDigits.substring(start);
    }
}
