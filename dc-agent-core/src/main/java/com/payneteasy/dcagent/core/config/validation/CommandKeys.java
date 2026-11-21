package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.payneteasy.dcagent.core.config.model.TKeyType;
import com.payneteasy.dcagent.core.util.Units;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code keys} of a command config — a strict schema, checked on the raw JSON (Gson would drop
 * an unknown field silently: a misspelt {@code maxTokenAge} would turn into the default, a misspelt
 * rule field into no condition at all).
 * <pre>
 * keys:
 *   - name: ci-static            # [0-9A-Za-z._-]{1,64}, unique
 *     type: SHA256
 *     hash: sha256:&lt;64 hex&gt;
 *   - name: gitlab-ci
 *     type: GITLAB_JWT
 *     issuer: code-clubber-me    # the file KEYS_DIR/&lt;issuer&gt;.json
 *     audience: dc-agent:host    # the token's aud
 *     maxTokenAge: 60m           # optional, now - iat; [1m, 24h], whole seconds
 *     runnerIds: [26, "27"]      # optional
 *     rules:                     # any rule; inside a rule — every condition
 *       - project_id: 913        # required in every rule
 *         ref_protected: true
 *         pipeline_source: [push]
 * </pre>
 * A rule value is a scalar or a non-empty list of scalars ("any of"), normalised by its field's
 * schema to the string GitLab puts into the claim: an id — an integer or a string of digits; a flag —
 * {@code true}/{@code false} or those strings; the rest — strings only (in JSON a number there is
 * refused; YAML scalars reach the agent as strings, so a YAML {@code ref: 2.0} is the branch "2.0").
 * The text {@code null} or {@code ~} is refused for any field, even quoted. Problems name fields, never values.
 */
public final class CommandKeys {

    public static final String KEYS     = "keys";
    public static final String API_KEYS = "apiKeys";

    public static final Duration DEFAULT_MAX_TOKEN_AGE = Duration.ofMinutes(60);
    public static final Duration MAX_TOKEN_AGE_LIMIT   = Duration.ofHours(24);
    public static final Duration MIN_TOKEN_AGE         = Duration.ofMinutes(1);

    private static final Pattern NAME   = Pattern.compile("[0-9A-Za-z._-]{1,64}");
    private static final Pattern ISSUER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,19}");

    private static final Set<String> SHA256_FIELDS = Set.of("name", "type", "hash");
    private static final Set<String> JWT_FIELDS    = Set.of("name", "type", "issuer", "audience", "maxTokenAge", "runnerIds", "rules");

    /** Rule fields and the schema of their values. */
    public enum RuleField { ID, FLAG, TEXT }

    public static final Map<String, RuleField> RULE_FIELDS = ruleFields();

    public static final String REQUIRED_RULE_FIELD = "project_id";

    private CommandKeys() {
    }

    /**
     * The key problems of a config object: exactly one of {@code apiKeys} (legacy, see
     * {@link CommandConfigValidator}) and {@code keys}. Only {@code keys} is checked here.
     *
     * @return true when the config uses {@code keys} (the caller then skips the {@code apiKeys} check)
     */
    public static boolean check(JsonObject aObject, List<String> aProblems, List<String> aWarnings) {
        // presence, not value: "keys": null next to apiKeys is a mistake to refuse, not "no keys"
        boolean hasApiKeys = aObject.has(API_KEYS);
        boolean hasKeys    = aObject.has(KEYS);
        if (hasApiKeys && hasKeys) {
            aProblems.add("\"apiKeys\" and \"keys\" together; keep one (\"keys\" replaces \"apiKeys\")");
            return true;
        }
        if (!hasKeys) {
            return false;
        }
        JsonElement element = aObject.get(KEYS);
        if (!element.isJsonArray()) {
            aProblems.add("\"keys\" must be a list");
            return true;
        }
        JsonArray keys = element.getAsJsonArray();
        if (keys.isEmpty()) {
            aProblems.add("\"keys\" is empty");
            return true;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            String where = "keys[" + i + "]";
            if (!keys.get(i).isJsonObject()) {
                aProblems.add(where + " must be an object");
                continue;
            }
            checkKey(where, keys.get(i).getAsJsonObject(), names, aProblems, aWarnings);
        }
        return true;
    }

    private static void checkKey(String aIndex, JsonObject aKey, Set<String> aNames, List<String> aProblems, List<String> aWarnings) {
        String name  = string(aKey, "name");
        String where = aIndex;
        if (name == null || !NAME.matcher(name).matches()) {
            aProblems.add(where + ".name is required: [0-9A-Za-z._-]{1,64}");
        } else if (!aNames.add(name)) {
            aProblems.add(where + ".name \"" + name + "\" is used twice");
        } else {
            where = where + " (" + name + ")";
        }

        String typeText = string(aKey, "type");
        TKeyType type = null;
        if (typeText != null) {
            try {
                type = TKeyType.valueOf(typeText);
            } catch (IllegalArgumentException e) {
                type = null;
            }
        }
        if (type == null) {
            aProblems.add(where + ".type must be one of " + java.util.Arrays.toString(TKeyType.values()));
            return;
        }

        Set<String> allowed = type == TKeyType.SHA256 ? SHA256_FIELDS : JWT_FIELDS;
        for (String field : aKey.keySet()) {
            if (!allowed.contains(field)) {
                aProblems.add(where + ": field \"" + shown(field) + "\" is not valid for type " + type);
            }
        }

        if (type == TKeyType.SHA256) {
            String hash = string(aKey, "hash");
            if (hash == null || !ApiKeyHash.isHashed(hash)) {
                aProblems.add(where + ".hash must be sha256: + 64 lowercase hex (dc-agent hash-key)");
            }
            return;
        }

        String issuer = string(aKey, "issuer");
        if (issuer == null || !ISSUER.matcher(issuer).matches()) {
            aProblems.add(where + ".issuer is required: [a-z0-9][a-z0-9_-]{0,63} (the file <issuer>.json in the keys directory)");
        }
        String audience = string(aKey, "audience");
        if (audience == null || audience.isBlank() || audience.length() > 256) {
            aProblems.add(where + ".audience is required: the aud of the token (id_tokens.<VAR>.aud in .gitlab-ci.yml)");
        }
        if (aKey.has("maxTokenAge")) {
            String text = string(aKey, "maxTokenAge");
            Duration age = null;
            try {
                age = text == null ? null : Units.parseDuration(text);
            } catch (RuntimeException e) {
                age = null;
            }
            // whole seconds (the token's iat is), at least a minute: a shorter age refuses every token
            if (age == null || age.getNano() != 0 || age.compareTo(MIN_TOKEN_AGE) < 0 || age.compareTo(MAX_TOKEN_AGE_LIMIT) > 0) {
                aProblems.add(where + ".maxTokenAge must be a duration of whole seconds in [1m, 24h], e.g. 15m");
            }
        }
        if (aKey.has("runnerIds") && runnerIds(aKey.get("runnerIds")) == null) {
            aProblems.add(where + ".runnerIds must be a non-empty list of runner ids (integers)");
        }
        checkRules(where, aKey.get("rules"), aProblems, aWarnings);
    }

    private static void checkRules(String aWhere, JsonElement aRules, List<String> aProblems, List<String> aWarnings) {
        if (aRules == null || !aRules.isJsonArray() || aRules.getAsJsonArray().isEmpty()) {
            aProblems.add(aWhere + ".rules must be a non-empty list of rules");
            return;
        }
        JsonArray rules = aRules.getAsJsonArray();
        for (int j = 0; j < rules.size(); j++) {
            String where = aWhere + ".rules[" + j + "]";
            if (!rules.get(j).isJsonObject() || rules.get(j).getAsJsonObject().isEmpty()) {
                aProblems.add(where + " must be a non-empty object");
                continue;
            }
            JsonObject rule = rules.get(j).getAsJsonObject();
            for (Map.Entry<String, JsonElement> condition : rule.entrySet()) {
                String    field  = condition.getKey();
                RuleField schema = RULE_FIELDS.get(field);
                if (schema == null) {
                    aProblems.add(where + ": unknown field \"" + shown(field) + "\"; one of " + RULE_FIELDS.keySet());
                } else if (values(schema, condition.getValue()) == null) {
                    aProblems.add(where + "." + field + ": " + describe(schema));
                }
            }
            if (!rule.has(REQUIRED_RULE_FIELD)) {
                aProblems.add(where + ": " + REQUIRED_RULE_FIELD + " is required in every rule"
                        + " (aud, environment and the rest are chosen by whoever writes .gitlab-ci.yml)");
            }
            if (!rule.has("ref_protected")) {
                aWarnings.add(where + ": no ref_protected — a job of any branch of the project passes");
            }
            if (!rule.has("pipeline_source")) {
                aWarnings.add(where + ": no pipeline_source — merge request, schedule, trigger and other pipelines pass too");
            } else {
                List<String> sources = values(RuleField.TEXT, rule.get("pipeline_source"));
                if (sources != null && sources.contains("merge_request_event")) {
                    aWarnings.add(where + ": pipeline_source merge_request_event — a merge request from a fork can run its own CI config");
                }
            }
        }
    }

    /**
     * The normalised values of a rule condition, or null when the value does not fit the schema: a
     * scalar or a non-empty list of scalars.
     */
    public static List<String> values(RuleField aSchema, JsonElement aValue) {
        List<String> values = new ArrayList<>();
        if (aValue != null && aValue.isJsonArray()) {
            JsonArray array = aValue.getAsJsonArray();
            if (array.isEmpty()) {
                return null;
            }
            for (JsonElement item : array) {
                String value = scalar(aSchema, item);
                if (value == null) {
                    return null;
                }
                values.add(value);
            }
            return values;
        }
        String value = scalar(aSchema, aValue);
        return value == null ? null : List.of(value);
    }

    /** {@code runnerIds} normalised to decimal strings, or null when it is not a non-empty list of ids. */
    public static List<String> runnerIds(JsonElement aValue) {
        if (aValue == null || !aValue.isJsonArray()) {
            return null;
        }
        return values(RuleField.ID, aValue);
    }

    private static String scalar(RuleField aSchema, JsonElement aValue) {
        if (aValue == null || !aValue.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = aValue.getAsJsonPrimitive();
        switch (aSchema) {
            case ID -> {
                if (primitive.isString()) {
                    return DIGITS.matcher(primitive.getAsString()).matches() ? primitive.getAsString() : null;
                }
                // a JSON number only as an integer literal: YAML hands "913.0" over as text, and both
                // formats must read the same (913.0, 9.13e2 are refused in either)
                if (primitive.isNumber()) {
                    String literal = primitive.getAsString();
                    return DIGITS.matcher(literal).matches() ? literal : null;
                }
                return null;
            }
            case FLAG -> {
                if (primitive.isBoolean()) {
                    return String.valueOf(primitive.getAsBoolean());
                }
                if (primitive.isString() && ("true".equals(primitive.getAsString()) || "false".equals(primitive.getAsString()))) {
                    return primitive.getAsString();
                }
                return null;
            }
            default -> {
                return primitive.isString() && !primitive.getAsString().isEmpty() && !isNullLiteral(primitive.getAsString())
                        ? primitive.getAsString() : null;
            }
        }
    }

    private static String describe(RuleField aSchema) {
        return switch (aSchema) {
            case ID   -> "an id (integer) or a non-empty list of ids";
            case FLAG -> "true, false or a list of them";
            case TEXT -> "a string (quote numbers in YAML) or a non-empty list of strings";
        };
    }

    /** True when the config object has a {@code keys} field — whatever its value, null included. */
    public static boolean hasKeys(JsonObject aObject) {
        return aObject.has(KEYS);
    }

    /**
     * {@code keys} for display — "name (TYPE)" per entry, nothing else: hashes, issuers and rules
     * stay on the host.
     */
    public static String summary(JsonElement aKeys) {
        if (aKeys == null || !aKeys.isJsonArray()) {
            return "";
        }
        List<String> entries = new ArrayList<>();
        for (JsonElement key : aKeys.getAsJsonArray()) {
            if (key.isJsonObject()) {
                String name = string(key.getAsJsonObject(), "name");
                String type = string(key.getAsJsonObject(), "type");
                entries.add((name == null ? "?" : name) + " (" + (type == null ? "?" : type) + ")");
            }
        }
        return String.join(", ", entries);
    }

    /**
     * A string field's value; null when missing, not a string, or a YAML null spelt as text — the YAML
     * converter keeps every scalar as a string, so {@code issuer: null} arrives as "null".
     */
    private static String string(JsonObject aObject, String aField) {
        JsonElement element = aObject.get(aField);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return isNullLiteral(value) ? null : value;
    }

    private static boolean isNullLiteral(String aValue) {
        return "null".equals(aValue) || "Null".equals(aValue) || "NULL".equals(aValue) || "~".equals(aValue);
    }

    /**
     * A field name for a message. A mistaken name may be a key or a hash, whatever its alphabet: it is
     * shown only when it is a typo of a known field (at most two edits away), otherwise as {@code <field>}.
     */
    private static String shown(String aField) {
        for (String known : KNOWN_FIELDS) {
            if (Math.abs(known.length() - aField.length()) <= 2 && distance(known, aField) <= 2) {
                return aField;
            }
        }
        return "<field>";
    }

    private static final Set<String> KNOWN_FIELDS = knownFields();

    private static Set<String> knownFields() {
        Set<String> fields = new HashSet<>(JWT_FIELDS);
        fields.addAll(SHA256_FIELDS);
        fields.addAll(ruleFields().keySet());
        return Collections.unmodifiableSet(fields);
    }

    /** Levenshtein distance of two short strings. */
    private static int distance(String aLeft, String aRight) {
        int[] previous = new int[aRight.length() + 1];
        int[] current  = new int[aRight.length() + 1];
        for (int j = 0; j <= aRight.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= aLeft.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= aRight.length(); j++) {
                int cost = aLeft.charAt(i - 1) == aRight.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[aRight.length()];
    }

    private static Map<String, RuleField> ruleFields() {
        Map<String, RuleField> fields = new LinkedHashMap<>();
        fields.put("project_id", RuleField.ID);
        fields.put("namespace_id", RuleField.ID);
        fields.put("project_path", RuleField.TEXT);
        fields.put("namespace_path", RuleField.TEXT);
        fields.put("ref", RuleField.TEXT);
        fields.put("ref_type", RuleField.TEXT);
        fields.put("ref_protected", RuleField.FLAG);
        fields.put("pipeline_source", RuleField.TEXT);
        fields.put("environment", RuleField.TEXT);
        fields.put("environment_protected", RuleField.FLAG);
        fields.put("environment_action", RuleField.TEXT);
        fields.put("deployment_tier", RuleField.TEXT);
        fields.put("ci_config_ref_uri", RuleField.TEXT);
        return Collections.unmodifiableMap(fields);
    }
}
