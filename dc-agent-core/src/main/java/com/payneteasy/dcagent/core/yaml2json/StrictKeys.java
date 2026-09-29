package com.payneteasy.dcagent.core.yaml2json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds keys of a JSON tree that the target model does not have — Gson ignores them silently, so a
 * typo in a config would switch a setting off without a word. Walks the JSON along the Java types:
 * a model class allows its declared fields (Gson names, no {@code @SerializedName} in the models);
 * a {@code Map} allows any key; a {@code List}/array checks its elements; strings, numbers,
 * booleans and enums are leaves. A field or a class with {@code @JsonAdapter} is a leaf too: the
 * adapter reads (and checks) its value itself.
 */
public final class StrictKeys {

    private StrictKeys() {
    }

    /**
     * @return one line per unknown key, e.g. {@code volumes[0].directoryOrCreate.readOnly: unknown key (did you mean 'readonly'?)}
     */
    public static List<String> unknownKeys(JsonElement aJson, Class<?> aRoot) {
        List<String> errors = new ArrayList<>();
        check(aJson, aRoot, "", errors);
        return errors;
    }

    private static void check(JsonElement aJson, Type aType, String aPath, List<String> aErrors) {
        if (aJson == null || aJson.isJsonNull()) {
            return;
        }

        if (aType instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) aType;
            Class<?>          raw           = (Class<?>) parameterized.getRawType();
            Type[]            arguments     = parameterized.getActualTypeArguments();
            if (Map.class.isAssignableFrom(raw) && aJson.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : aJson.getAsJsonObject().entrySet()) {
                    check(entry.getValue(), arguments[1], child(aPath, entry.getKey()), aErrors);
                }
            } else if (Collection.class.isAssignableFrom(raw) && aJson.isJsonArray()) {
                checkElements(aJson.getAsJsonArray(), arguments[0], aPath, aErrors);
            }
            return;
        }

        if (aType instanceof GenericArrayType) {
            if (aJson.isJsonArray()) {
                checkElements(aJson.getAsJsonArray(), ((GenericArrayType) aType).getGenericComponentType(), aPath, aErrors);
            }
            return;
        }

        if (!(aType instanceof Class)) {
            return;
        }
        Class<?> type = (Class<?>) aType;
        if (type.isArray()) {
            if (aJson.isJsonArray()) {
                checkElements(aJson.getAsJsonArray(), type.getComponentType(), aPath, aErrors);
            }
            return;
        }
        if (isLeaf(type) || !aJson.isJsonObject()) {
            return;
        }

        Map<String, Field> fields = fields(type);
        for (Map.Entry<String, JsonElement> entry : aJson.getAsJsonObject().entrySet()) {
            String path  = child(aPath, entry.getKey());
            Field  field = fields.get(entry.getKey());
            if (field == null) {
                aErrors.add(path + ": unknown key" + suggestion(entry.getKey(), fields.keySet()));
            } else if (!field.isAnnotationPresent(JsonAdapter.class)) {
                check(entry.getValue(), field.getGenericType(), path, aErrors);
            }
        }
    }

    private static void checkElements(JsonArray aArray, Type aElementType, String aPath, List<String> aErrors) {
        for (int i = 0; i < aArray.size(); i++) {
            check(aArray.get(i), aElementType, aPath + "[" + i + "]", aErrors);
        }
    }

    private static boolean isLeaf(Class<?> aType) {
        return aType.isPrimitive()
                || aType.isEnum()
                || aType.getName().startsWith("java.")
                || aType.isAnnotationPresent(JsonAdapter.class);
    }

    /** Declared fields of the class and its superclasses, as Gson maps them. */
    private static Map<String, Field> fields(Class<?> aType) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> type = aType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                    continue;
                }
                fields.putIfAbsent(field.getName(), field);
            }
        }
        return fields;
    }

    private static String child(String aPath, String aKey) {
        return aPath.isEmpty() ? aKey : aPath + "." + aKey;
    }

    private static String suggestion(String aKey, Collection<String> aKnown) {
        String best         = null;
        int    bestDistance = Integer.MAX_VALUE;
        String key          = aKey.toLowerCase(Locale.ROOT);
        for (String known : aKnown) {
            String candidate = known.toLowerCase(Locale.ROOT);
            // a shortened name (linkToHostDir) is closer to its full form than to a similar word
            int distance = candidate.equals(key) ? 0
                    : candidate.startsWith(key) || key.startsWith(candidate) ? 1
                    : distance(key, candidate);
            if (distance < bestDistance) {
                best         = known;
                bestDistance = distance;
            }
        }
        if (best == null || bestDistance > Math.max(2, aKey.length() / 4)) {
            return "; known keys: " + String.join(", ", aKnown);
        }
        return " (did you mean '" + best + "'?)";
    }

    /** Levenshtein distance. */
    private static int distance(String aLeft, String aRight) {
        int[] previous = new int[aRight.length() + 1];
        int[] current  = new int[aRight.length() + 1];
        for (int j = 0; j <= aRight.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= aLeft.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= aRight.length(); j++) {
                int substitution = previous[j - 1] + (aLeft.charAt(i - 1) == aRight.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current  = swap;
        }
        return previous[aRight.length()];
    }
}
