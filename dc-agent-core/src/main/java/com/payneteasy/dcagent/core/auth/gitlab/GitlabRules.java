package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.model.TCommandKey;
import com.payneteasy.dcagent.core.config.validation.CommandKeys;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The claims of a verified token against a {@code GITLAB_JWT} key: {@code runnerIds} (when set), then
 * its rules — any rule, every condition of it. A condition holds when the claim is a JSON string (the
 * form GitLab gives every condition field) equal to the condition's value or one of its list; a
 * missing claim or a claim of another JSON type fails it. Values are normalised by
 * {@link CommandKeys#values}, as the validator checked them.
 */
public final class GitlabRules {

    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,19}");

    private GitlabRules() {
    }

    /** The index of the first matching rule. */
    public static int match(JsonObject aClaims, TCommandKey aKey) throws JwtRejectedException {
        if (aKey.getRunnerIds() != null) {
            List<String> allowed = CommandKeys.runnerIds(toArray(aKey.getRunnerIds()));
            JsonElement  runner  = aClaims.get("runner_id");
            String       id      = runner != null && runner.isJsonPrimitive() && runner.getAsJsonPrimitive().isNumber()
                    && INTEGER.matcher(runner.getAsString()).matches() ? runner.getAsString() : null;
            if (allowed == null || id == null || !allowed.contains(id)) {
                throw new JwtRejectedException("runner_id is not in runnerIds");
            }
        }
        List<Map<String, JsonElement>> rules = aKey.getRules();
        if (rules == null || rules.isEmpty()) {
            throw new JwtRejectedException("no rules");
        }
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            String failed = failedCondition(aClaims, rules.get(i));
            if (failed == null) {
                return i;
            }
            why.append(i == 0 ? "" : ", ").append("rules[").append(i).append("] ").append(failed);
        }
        throw new JwtRejectedException("no rule matches: " + why);
    }

    /** The first condition the claims fail ({@code field}), or null when the rule matches. */
    private static String failedCondition(JsonObject aClaims, Map<String, JsonElement> aRule) {
        if (aRule == null || aRule.isEmpty() || !aRule.containsKey(CommandKeys.REQUIRED_RULE_FIELD)) {
            return "is not a usable rule";
        }
        for (Map.Entry<String, JsonElement> condition : aRule.entrySet()) {
            CommandKeys.RuleField schema = CommandKeys.RULE_FIELDS.get(condition.getKey());
            List<String>          values = schema == null ? null : CommandKeys.values(schema, condition.getValue());
            if (values == null) {
                return condition.getKey() + " (invalid in the config)";
            }
            JsonElement claim = aClaims.get(condition.getKey());
            if (claim == null || !claim.isJsonPrimitive() || !claim.getAsJsonPrimitive().isString()
                    || !values.contains(claim.getAsString())) {
                return condition.getKey();
            }
        }
        return null;
    }

    private static com.google.gson.JsonArray toArray(List<JsonElement> aItems) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (JsonElement item : aItems) {
            array.add(item);
        }
        return array;
    }
}
