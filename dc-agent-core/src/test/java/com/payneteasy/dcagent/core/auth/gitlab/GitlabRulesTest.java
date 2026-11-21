package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.payneteasy.dcagent.core.config.model.TCommandKey;
import com.payneteasy.dcagent.core.config.model.TKeyType;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GitlabRulesTest {

    private static JsonObject claims(String aExtra) {
        return JsonParser.parseString(TestTokens.payload(1000, aExtra)).getAsJsonObject();
    }

    private static Map<String, JsonElement> rule(String aJson) {
        Map<String, JsonElement> rule = new LinkedHashMap<>();
        JsonParser.parseString(aJson).getAsJsonObject().entrySet().forEach(entry -> rule.put(entry.getKey(), entry.getValue()));
        return rule;
    }

    private static TCommandKey key(List<JsonElement> aRunners, String... aRules) {
        return TCommandKey.builder().name("gitlab").type(TKeyType.GITLAB_JWT).runnerIds(aRunners)
                .rules(java.util.Arrays.stream(aRules).map(GitlabRulesTest::rule).toList()).build();
    }

    @Test
    public void every_condition_of_a_rule_must_hold() throws Exception {
        assertThat(GitlabRules.match(claims(""), key(null, "{\"project_id\":913,\"ref_protected\":true,\"pipeline_source\":[\"push\",\"web\"]}"))).isZero();
        assertThatThrownBy(() -> GitlabRules.match(claims(""), key(null, "{\"project_id\":913,\"ref\":\"release\"}")))
                .hasMessageContaining("rules[0] ref");
    }

    @Test
    public void any_rule_may_match() throws Exception {
        assertThat(GitlabRules.match(claims(""), key(null, "{\"project_id\":914}", "{\"project_id\":\"913\"}"))).isEqualTo(1);
    }

    @Test
    public void a_claim_is_compared_only_as_a_json_string() {
        // the issuer signed it, but ["913"] is not the string "913"
        JsonObject bent = claims("");
        bent.add("project_id", JsonParser.parseString("[\"913\"]"));
        assertThatThrownBy(() -> GitlabRules.match(bent, key(null, "{\"project_id\":913}"))).hasMessageContaining("project_id");
        JsonObject numeric = claims("");
        numeric.add("project_id", JsonParser.parseString("913"));
        assertThatThrownBy(() -> GitlabRules.match(numeric, key(null, "{\"project_id\":913}"))).hasMessageContaining("project_id");
        JsonObject missing = claims("");
        missing.remove("ref_protected");
        assertThatThrownBy(() -> GitlabRules.match(missing, key(null, "{\"project_id\":913,\"ref_protected\":false}"))).hasMessageContaining("ref_protected");
    }

    @Test
    public void runner_ids_limit_where_the_token_was_issued() throws Exception {
        assertThat(GitlabRules.match(claims(""), key(List.of(JsonParser.parseString("26")), "{\"project_id\":913}"))).isZero();
        assertThatThrownBy(() -> GitlabRules.match(claims(""), key(List.of(JsonParser.parseString("\"27\"")), "{\"project_id\":913}")))
                .hasMessageContaining("runner_id");
        JsonObject asText = claims("");
        asText.addProperty("runner_id", "26");
        assertThatThrownBy(() -> GitlabRules.match(asText, key(List.of(JsonParser.parseString("26")), "{\"project_id\":913}")))
                .hasMessageContaining("runner_id");
    }
}
