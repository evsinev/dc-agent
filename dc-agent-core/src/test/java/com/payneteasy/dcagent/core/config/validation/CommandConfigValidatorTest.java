package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every refusal has a twin that differs only in the checked field and passes — so a test does not
 * pass merely because something else in the config is wrong.
 */
public class CommandConfigValidatorTest {

    private static final String SECRET = "the-secret-key-0123456789abcdef0123";

    private final CommandConfigValidator validator = TestValidators.validator(Gsons.PRETTY_GSON);

    private static JsonObject json(String aText) {
        return JsonParser.parseString(aText).getAsJsonObject();
    }

    private static JsonObject zipArchive(String aTypeField) {
        return json("{" + aTypeField + "\"apiKeys\":{\"" + SECRET + "\":\"ci\"},\"dir\":\"/opt/site\"}");
    }

    private List<String> problems(String aName, JsonObject aObject) {
        List<String> problems = validator.validate(aName, aObject, ConfigCheckMode.PRE_MIGRATION);
        assertThat(String.join(" ", problems)).doesNotContain(SECRET);
        return problems;
    }

    @Test
    public void type_must_be_present_and_is_never_guessed() {
        assertThat(problems("site", zipArchive("\"type\":\"ZIP_ARCHIVE\","))).isEmpty();
        assertThat(problems("site", zipArchive(""))).singleElement().asString().startsWith("no \"type\"");
        assertThat(problems("site", zipArchive("\"type\":null,"))).singleElement().asString().startsWith("no \"type\"");
    }

    @Test
    public void unknown_and_non_string_types_are_refused() {
        assertThat(problems("site", zipArchive("\"type\":\"ZIP_ARHCIVE\","))).singleElement().asString().startsWith("unknown \"type\"");
        assertThat(problems("site", zipArchive("\"type\":\"zip_archive\","))).singleElement().asString().startsWith("unknown \"type\"");
        assertThat(problems("site", zipArchive("\"type\":5,"))).containsExactly("\"type\" must be a string");
    }

    @Test
    public void types_no_endpoint_serves_are_refused() {
        for (String type : new String[]{"DOCKER_PUSH", "DOCKER_CHECK", "SERVICE"}) {
            assertThat(problems("site", zipArchive("\"type\":\"" + type + "\","))).singleElement().asString()
                    .contains("not served by any agent endpoint");
        }
    }

    @Test
    public void fetch_url_and_docker_types_belong_to_their_fixed_names() {
        JsonObject fetch  = json("{\"type\":\"FETCH_URL\",\"apiKeys\":{\"k\":\"ci\"}}");
        JsonObject docker = json("{\"type\":\"DOCKER\",\"apiKeys\":{\"k\":\"ci\"}}");

        assertThat(problems("fetch-url", fetch)).isEmpty();
        assertThat(problems("proxy", fetch)).singleElement().asString().contains("fetch-url");
        assertThat(problems("dc-docker", docker)).isEmpty();
        assertThat(problems("svc", docker)).singleElement().asString().contains("dc-docker");
    }

    @Test
    public void api_keys_must_be_a_non_empty_object_of_strings() {
        String type = "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/opt/site\"";
        assertThat(problems("site", json(type + ",\"apiKeys\":{\"" + SECRET + "\":\"ci\"}}"))).isEmpty();
        assertThat(problems("site", json(type + "}"))).containsExactly("no \"apiKeys\"");
        assertThat(problems("site", json(type + ",\"apiKeys\":{}}"))).containsExactly("\"apiKeys\" is empty");
        assertThat(problems("site", json(type + ",\"apiKeys\":[\"" + SECRET + "\"]}"))).anySatisfy(p -> assertThat(p).contains("must be an object"));
        assertThat(problems("site", json(type + ",\"apiKeys\":{\"" + SECRET + "\":1}}"))).singleElement().asString().contains("owner must be a string");
    }

    @Test
    public void fields_must_read_into_the_model_of_the_type() {
        String base = "{\"type\":\"ZIP_DIRS\",\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/opt/site\",\"delete\":";
        assertThat(problems("site", json(base + "true}"))).isEmpty();
        // a YAML value arrives as a string; Gson reads it into the boolean
        assertThat(problems("site", json(base + "\"true\"}"))).isEmpty();
        assertThat(problems("site", json(base + "[]}"))).singleElement().asString()
                .startsWith("the fields do not fit a ZIP_DIRS config").contains("$.delete");
    }

    @Test
    public void zip_archive_version_settings_are_checked() {
        String good = "{\"type\":\"ZIP_ARCHIVE_VERSION\",\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/opt/b\",\"versionFile\":\"/opt/b/current\",\"reloadUrl\":\"http://127.0.0.1:8080/reload\"";
        assertThat(problems("bundle", json(good + "}"))).isEmpty();
        assertThat(problems("bundle", json("{\"type\":\"ZIP_ARCHIVE_VERSION\",\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/opt/b\",\"reloadUrl\":\"http://127.0.0.1:8080/reload\"}")))
                .singleElement().asString().contains("versionFile");
        assertThat(problems("bundle", json(good + ",\"waitTimeout\":\"1h\"}")))
                .singleElement().asString().contains("waitTimeout");
    }

    @Test
    public void an_invalid_command_name_is_a_problem() {
        assertThat(problems("site", zipArchive("\"type\":\"ZIP_ARCHIVE\","))).isEmpty();
        assertThat(problems("si te", zipArchive("\"type\":\"ZIP_ARCHIVE\","))).singleElement().asString().contains("command name");
    }

    @Test
    public void a_blank_service_dir_falls_back_to_the_service_name_like_the_servlet() {
        CommandConfigValidator validator = new CommandConfigValidator(Gsons.PRETTY_GSON, Duration.ofMinutes(10),
                new CommandPathPolicy(List.of(java.nio.file.Path.of("/opt")), List.of(), List.of(java.nio.file.Path.of("/service"))));
        String base = "{\"type\":\"JAR\",\"apiKeys\":{\"k\":\"ci\"},\"jarFilename\":\"/opt/app/app.jar\"";

        assertThat(validator.validate("app", json(base + ",\"serviceName\":\"app\",\"serviceDir\":\" \"}"), ConfigCheckMode.PRE_MIGRATION)).isEmpty();
        assertThat(validator.validate("app", json(base + ",\"serviceName\":\"app\"}"), ConfigCheckMode.PRE_MIGRATION)).isEmpty();
        // U+2003: not blank for Strings.hasText (trim) — the servlet uses it as a serviceDir, so does the check
        assertThat(validator.validate("app", json(base + ",\"serviceName\":\"app\",\"serviceDir\":\"\u2003\"}"), ConfigCheckMode.PRE_MIGRATION)).isNotEmpty();
        assertThat(validator.validate("app", json(base + ",\"serviceName\":\"\",\"serviceDir\":\"\"}"), ConfigCheckMode.PRE_MIGRATION))
                .containsExactly("\"serviceName\" or \"serviceDir\" is required");
    }

    @Test
    public void a_plaintext_key_counts_by_mode() {
        JsonObject plain  = json("{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + SECRET + "\":\"ci\"},\"dir\":\"/opt/site\"}");
        JsonObject hashed = json("{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + ApiKeyHash.hash(SECRET) + "\":\"ci\"},\"dir\":\"/opt/site\"}");

        for (ConfigCheckMode mode : ConfigCheckMode.values()) {
            assertThat(validator.validate("site", hashed, mode)).as(mode.name()).isEmpty();
        }
        assertThat(validator.validate("site", plain, ConfigCheckMode.PRE_MIGRATION)).isEmpty();
        assertThat(validator.validate("site", plain, ConfigCheckMode.REQUEST)).isEmpty();
        assertThat(validator.validate("site", plain, ConfigCheckMode.STRICT)).singleElement().asString()
                .contains("1 plaintext key(s)").doesNotContain(SECRET);
    }

    @Test
    public void a_malformed_hash_is_a_problem_in_every_mode_never_a_key_to_hash() {
        String malformed = ApiKeyHash.hash(SECRET).toUpperCase();
        JsonObject config = json("{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + malformed + "\":\"ci\"},\"dir\":\"/opt/site\"}");

        for (ConfigCheckMode mode : ConfigCheckMode.values()) {
            assertThat(validator.validate("site", config, mode)).as(mode.name()).singleElement().asString()
                    .contains("starts with sha256: but is not").doesNotContain(malformed);
        }
    }
}
