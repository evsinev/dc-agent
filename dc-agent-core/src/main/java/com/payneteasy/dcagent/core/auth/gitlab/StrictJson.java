package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * JSON read the strict way for signed data: strict UTF-8 (no replacement characters), RFC 8259
 * syntax, no duplicate names in any object (compared after unescaping — "a" and "a" are the
 * same name), a nesting limit, nothing after the value. Gson's own parser keeps the last of two
 * duplicates, which would let a token carry two different {@code project_id}s.
 */
final class StrictJson {

    static final int MAX_DEPTH = 32;

    private StrictJson() {
    }

    static JsonObject object(byte[] aUtf8, String aWhat) throws JwtRejectedException {
        String text;
        try {
            text = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(aUtf8)).toString();
        } catch (CharacterCodingException e) {
            throw new JwtRejectedException(aWhat + " is not valid UTF-8");
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new JwtRejectedException(aWhat + " is not a JSON object");
            }
            JsonElement value = read(reader, 0, aWhat);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JwtRejectedException(aWhat + " has data after the JSON object");
            }
            return value.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new JwtRejectedException(aWhat + " is not strict JSON (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static JsonElement read(JsonReader aReader, int aDepth, String aWhat) throws IOException, JwtRejectedException {
        if (aDepth > MAX_DEPTH) {
            throw new JwtRejectedException(aWhat + " is nested deeper than " + MAX_DEPTH);
        }
        switch (aReader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                aReader.beginObject();
                while (aReader.hasNext()) {
                    String name = aReader.nextName();
                    if (object.has(name)) {
                        throw new JwtRejectedException(aWhat + " has a duplicate name");
                    }
                    object.add(name, read(aReader, aDepth + 1, aWhat));
                }
                aReader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                aReader.beginArray();
                while (aReader.hasNext()) {
                    array.add(read(aReader, aDepth + 1, aWhat));
                }
                aReader.endArray();
                return array;
            }
            case STRING  -> {
                return new JsonPrimitive(aReader.nextString());
            }
            case NUMBER  -> {
                return new JsonPrimitive(new LazilyParsedNumber(aReader.nextString()));
            }
            case BOOLEAN -> {
                return new JsonPrimitive(aReader.nextBoolean());
            }
            case NULL    -> {
                aReader.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new JwtRejectedException(aWhat + " has an unexpected token");
        }
    }
}
