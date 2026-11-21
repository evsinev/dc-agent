package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.payneteasy.dcagent.core.config.model.TKeyType;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strict schema of {@code keys}. Every refusal has a passing twin that differs only in the
 * checked field — a test does not pass because something else in the config is wrong.
 */
public class CommandKeysTest {

    private static final String HASH = ApiKeyHash.hash("the-secret-key-0123456789abcdef0123");

    private final CommandConfigValidator validator = TestValidators.validator(Gsons.PRETTY_GSON);

    private static final String STATIC = "{\"name\":\"ci-static\",\"type\":\"SHA256\",\"hash\":\"" + HASH + "\"}";

    private static String jwt(String aExtra, String aRules) {
        return "{\"name\":\"gitlab-ci\",\"type\":\"GITLAB_JWT\",\"issuer\":\"code-clubber-me\",\"audience\":\"dc-agent:host\""
                + aExtra + ",\"rules\":" + aRules + "}";
    }

    private static final String RULE = "{\"project_id\":913,\"ref_protected\":true,\"pipeline_source\":[\"push\"]}";

    private static JsonObject config(String aKeys) {
        return JsonParser.parseString("{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/opt/site\",\"keys\":" + aKeys + "}").getAsJsonObject();
    }

    private List<String> problems(String aKeys) {
        return validator.validate("site", config(aKeys), ConfigCheckMode.STRICT);
    }

    private List<String> warnings(String aKeys) {
        List<String> warnings = new ArrayList<>();
        assertThat(validator.validate("site", config(aKeys), ConfigCheckMode.STRICT, warnings)).isEmpty();
        return warnings;
    }

    @Test
    public void a_static_and_a_gitlab_key_pass_and_read_into_the_model() {
        assertThat(problems("[" + STATIC + "," + jwt(",\"maxTokenAge\":\"15m\",\"runnerIds\":[26,\"27\"]", "[" + RULE + "]") + "]")).isEmpty();

        TZipArchiveConfig model = Gsons.PRETTY_GSON.fromJson(config("[" + STATIC + "]"), TZipArchiveConfig.class);
        assertThat(model.getKeys()).singleElement().satisfies(key -> {
            assertThat(key.getType()).isEqualTo(TKeyType.SHA256);
            assertThat(key.getHash()).isEqualTo(HASH);
        });
    }

    @Test
    public void keys_and_api_keys_never_together() {
        JsonObject both = config("[" + STATIC + "]");
        both.add("apiKeys", JsonParser.parseString("{\"" + HASH + "\":\"ci\"}"));
        assertThat(validator.validate("site", both, ConfigCheckMode.STRICT)).singleElement().asString().contains("together");

        both.remove("keys");
        assertThat(validator.validate("site", both, ConfigCheckMode.STRICT)).isEmpty();
    }

    @Test
    public void keys_must_be_a_non_empty_list_of_objects() {
        assertThat(problems("{}")).containsExactly("\"keys\" must be a list");
        assertThat(problems("[]")).containsExactly("\"keys\" is empty");
        assertThat(problems("[\"" + HASH + "\"]")).containsExactly("keys[0] must be an object");
        assertThat(problems("[" + STATIC + "]")).isEmpty();
    }

    @Test
    public void names_are_required_valid_and_unique() {
        assertThat(problems("[" + STATIC.replace("\"name\":\"ci-static\",", "") + "]")).singleElement().asString().contains(".name is required");
        assertThat(problems("[" + STATIC.replace("ci-static", "ci static") + "]")).singleElement().asString().contains(".name is required");
        assertThat(problems("[" + STATIC + "," + STATIC + "]")).singleElement().asString().contains("used twice");
        assertThat(problems("[" + STATIC + "," + STATIC.replace("ci-static", "ci-static-2") + "]")).isEmpty();
    }

    @Test
    public void type_is_required_and_known() {
        assertThat(problems("[" + STATIC.replace("SHA256", "SHA-256") + "]")).singleElement().asString().contains(".type must be one of");
        assertThat(problems("[" + STATIC.replace("\"type\":\"SHA256\",", "") + "]")).singleElement().asString().contains(".type must be one of");
    }

    @Test
    public void a_static_key_holds_a_hash_and_nothing_else() {
        assertThat(problems("[" + STATIC.replace(HASH, "the-secret-key-0123456789abcdef0123") + "]")).singleElement().asString()
                .contains(".hash must be sha256:").doesNotContain("the-secret");
        assertThat(problems("[" + STATIC.replace("}", ",\"rules\":[" + RULE + "]}") + "]")).singleElement().asString()
                .contains("field \"rules\" is not valid for type SHA256");
    }

    @Test
    public void a_gitlab_key_needs_issuer_audience_and_rules() {
        assertThat(problems("[" + jwt("", "[" + RULE + "]") + "]")).isEmpty();
        assertThat(problems("[" + jwt("", "[" + RULE + "]").replace("\"issuer\":\"code-clubber-me\",", "") + "]")).singleElement().asString().contains(".issuer is required");
        assertThat(problems("[" + jwt("", "[" + RULE + "]").replace("code-clubber-me", "../etc/passwd") + "]")).singleElement().asString().contains(".issuer is required");
        assertThat(problems("[" + jwt("", "[" + RULE + "]").replace(",\"audience\":\"dc-agent:host\"", "") + "]")).singleElement().asString().contains(".audience is required");
        assertThat(problems("[" + jwt("", "[]") + "]")).singleElement().asString().contains(".rules must be a non-empty list");
        assertThat(problems("[" + jwt("", "[{}]") + "]")).singleElement().asString().contains("must be a non-empty object");
    }

    @Test
    public void a_misspelt_field_is_a_problem_not_a_default() {
        assertThat(problems("[" + jwt(",\"maxTokenAg\":\"5m\"", "[" + RULE + "]") + "]")).singleElement().asString()
                .contains("field \"maxTokenAg\" is not valid");
        assertThat(problems("[" + jwt(",\"runnerId\":[26]", "[" + RULE + "]") + "]")).singleElement().asString()
                .contains("field \"runnerId\" is not valid");
        assertThat(problems("[" + jwt("", "[" + RULE.replace("ref_protected", "ref_protect") + "]") + "]")).anySatisfy(p ->
                assertThat(p).contains("unknown field \"ref_protect\""));
    }

    @Test
    public void max_token_age_is_a_duration_up_to_a_day() {
        assertThat(problems("[" + jwt(",\"maxTokenAge\":\"24h\"", "[" + RULE + "]") + "]")).isEmpty();
        assertThat(problems("[" + jwt(",\"maxTokenAge\":\"1m\"", "[" + RULE + "]") + "]")).isEmpty();
        // a fraction of a second or less than a minute would refuse every token
        for (String bad : new String[]{"0s", "25h", "-5m", "soon", "15", "0.5s", "30s", "90.5s"}) {
            assertThat(problems("[" + jwt(",\"maxTokenAge\":\"" + bad + "\"", "[" + RULE + "]") + "]")).as(bad)
                    .singleElement().asString().contains(".maxTokenAge must be a duration");
        }
    }

    @Test
    public void runner_ids_are_a_non_empty_list_of_integers() {
        assertThat(problems("[" + jwt(",\"runnerIds\":[26,\"27\"]", "[" + RULE + "]") + "]")).isEmpty();
        for (String bad : new String[]{"[]", "26", "[\"ci-3\"]", "[26.5]", "[-1]"}) {
            assertThat(problems("[" + jwt(",\"runnerIds\":" + bad, "[" + RULE + "]") + "]")).as(bad)
                    .singleElement().asString().contains(".runnerIds must be");
        }
    }

    @Test
    public void every_rule_binds_a_project() {
        assertThat(problems("[" + jwt("", "[{\"ref_protected\":true}]") + "]")).singleElement().asString()
                .contains("project_id is required in every rule");
        assertThat(problems("[" + jwt("", "[{\"project_id\":\"913\"}]") + "]")).isEmpty();
    }

    @Test
    public void rule_values_follow_the_schema_of_their_field() {
        // ids: integers or digit strings; flags: booleans or "true"/"false"; the rest: strings
        assertThat(problems("[" + jwt("", "[{\"project_id\":[913,\"914\"],\"ref_protected\":\"true\",\"ref\":\"2.0\"}]") + "]")).isEmpty();
        assertThat(problems("[" + jwt("", "[{\"project_id\":\"payneteasy/x\"}]") + "]")).singleElement().asString().contains("project_id: an id");
        assertThat(problems("[" + jwt("", "[{\"project_id\":913,\"ref_protected\":\"yes\"}]") + "]")).singleElement().asString().contains("ref_protected: true, false");
        assertThat(problems("[" + jwt("", "[{\"project_id\":913,\"ref\":2.0}]") + "]")).singleElement().asString().contains("ref: a string");
        assertThat(problems("[" + jwt("", "[{\"project_id\":913,\"pipeline_source\":[]}]") + "]")).singleElement().asString().contains("pipeline_source:");
        assertThat(problems("[" + jwt("", "[{\"project_id\":913,\"environment\":{\"name\":\"x\"}}]") + "]")).singleElement().asString().contains("environment:");
    }

    @Test
    public void values_are_normalised_to_the_strings_gitlab_puts_into_claims() {
        assertThat(CommandKeys.values(CommandKeys.RuleField.ID, JsonParser.parseString("1284"))).containsExactly("1284");
        assertThat(CommandKeys.values(CommandKeys.RuleField.ID, JsonParser.parseString("1284.0"))).isNull();
        assertThat(CommandKeys.values(CommandKeys.RuleField.ID, JsonParser.parseString("9.13e2"))).isNull();
        assertThat(CommandKeys.values(CommandKeys.RuleField.ID, JsonParser.parseString("12.5"))).isNull();
        assertThat(CommandKeys.values(CommandKeys.RuleField.FLAG, JsonParser.parseString("true"))).containsExactly("true");
        assertThat(CommandKeys.values(CommandKeys.RuleField.TEXT, JsonParser.parseString("[\"push\",\"web\"]"))).containsExactly("push", "web");
        assertThat(CommandKeys.values(CommandKeys.RuleField.TEXT, JsonParser.parseString("[[\"push\"]]"))).isNull();
    }

    @Test
    public void wide_rules_are_valid_but_warned_about() {
        assertThat(warnings("[" + jwt("", "[" + RULE + "]") + "]")).isEmpty();
        assertThat(warnings("[" + jwt("", "[{\"project_id\":913,\"pipeline_source\":\"push\"}]") + "]")).singleElement().asString()
                .contains("no ref_protected");
        assertThat(warnings("[" + jwt("", "[{\"project_id\":913,\"ref_protected\":true}]") + "]")).singleElement().asString()
                .contains("no pipeline_source");
        assertThat(warnings("[" + jwt("", "[{\"project_id\":913,\"ref_protected\":true,\"pipeline_source\":[\"push\",\"merge_request_event\"]}]") + "]"))
                .singleElement().asString().contains("merge_request_event");
    }

    @Test
    public void the_summary_shows_names_and_types_only() {
        String summary = CommandKeys.summary(JsonParser.parseString("[" + STATIC + "," + jwt("", "[" + RULE + "]") + "]"));
        assertThat(summary).isEqualTo("ci-static (SHA256), gitlab-ci (GITLAB_JWT)");
        assertThat(summary).doesNotContain(HASH).doesNotContain("913").doesNotContain("code-clubber-me");
    }

    @Test
    public void a_null_keys_is_a_mistake_not_an_absence() {
        JsonObject withNull = JsonParser.parseString("{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/opt/site\",\"keys\":null}").getAsJsonObject();
        assertThat(validator.validate("site", withNull, ConfigCheckMode.STRICT)).containsExactly("\"keys\" must be a list");
        assertThat(CommandKeys.hasKeys(withNull)).isTrue();

        withNull.add("apiKeys", JsonParser.parseString("{\"" + HASH + "\":\"ci\"}"));
        assertThat(validator.validate("site", withNull, ConfigCheckMode.STRICT)).singleElement().asString().contains("together");
    }

    @Test
    public void a_yaml_null_spelt_as_text_is_not_a_value() {
        // the YAML converter keeps scalars as strings: "issuer: null" arrives as "null"
        assertThat(problems("[" + jwt("", "[" + RULE + "]").replace("code-clubber-me", "null") + "]")).singleElement().asString().contains(".issuer is required");
        assertThat(problems("[" + jwt("", "[" + RULE + "]").replace("dc-agent:host", "~") + "]")).singleElement().asString().contains(".audience is required");
        assertThat(problems("[" + jwt("", "[{\"project_id\":913,\"environment\":\"null\"}]") + "]")).singleElement().asString().contains("environment:");
    }

    @Test
    public void a_mistaken_field_name_that_may_be_a_key_is_not_shown() {
        String entry = "{\"name\":\"ci\",\"type\":\"SHA256\",\"hash\":\"" + HASH + "\",\"" + HASH + "\":\"ci\"}";
        assertThat(problems("[" + entry + "]")).singleElement().asString().contains("\"<field>\"").doesNotContain(HASH);
    }

    @Test
    public void paths_inside_a_mistaken_keys_object_are_masked() {
        assertThat(CommandConfigFile.maskGsonLocation("$.keys." + HASH)).isEqualTo("$.keys.<entry>");
        assertThat(CommandConfigFile.maskGsonLocation("$.keys[0]." + HASH)).isEqualTo("$.keys.<entry>");
        assertThat(CommandConfigFile.maskYamlError("keys[0]." + HASH + ": duplicate key")).doesNotContain(HASH);
        assertThat(CommandConfigFile.maskYamlError("keys." + HASH + ": duplicate key")).doesNotContain(HASH);
        assertThat(CommandConfigFile.maskPath("keys." + HASH)).isEqualTo("keys.<entry>");
        assertThat(CommandConfigFile.maskGsonLocation("$.apiKeys." + HASH)).isEqualTo("$.apiKeys.<key>");
    }

    @Test
    public void a_typo_of_a_known_field_is_shown_anything_else_is_not() {
        assertThat(problems("[" + jwt(",\"maxTokenAg\":\"5m\"", "[" + RULE + "]") + "]")).singleElement().asString().contains("\"maxTokenAg\"");
        String secret = "AbCdEfGhIjKlMnOpQrStUvWxYz012345";
        assertThat(problems("[" + jwt(",\"" + secret + "\":\"x\"", "[" + RULE + "]") + "]")).singleElement().asString()
                .contains("\"<field>\"").doesNotContain(secret);
    }

    @Test
    public void ids_read_the_same_from_json_and_yaml() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("keys-yaml");
        for (String id : new String[]{"913", "913.0"}) {
            java.nio.file.Path yaml = dir.resolve("site.yml");
            java.nio.file.Files.writeString(yaml, "type: ZIP_ARCHIVE\ndir: /opt/site\nkeys:\n  - name: gitlab-ci\n    type: GITLAB_JWT\n"
                    + "    issuer: code-clubber-me\n    audience: dc-agent:host\n    rules:\n      - project_id: " + id
                    + "\n        ref_protected: true\n        pipeline_source: [push]\n", java.nio.charset.StandardCharsets.UTF_8);
            List<String> fromYaml = validator.validate("site", CommandConfigFile.read(yaml), ConfigCheckMode.STRICT);
            List<String> fromJson = problems("[" + jwt("", "[{\"project_id\":" + id + ",\"ref_protected\":true,\"pipeline_source\":[\"push\"]}]") + "]");
            assertThat(fromYaml.isEmpty()).as(id).isEqualTo(fromJson.isEmpty()).isEqualTo("913".equals(id));
        }
    }
}
