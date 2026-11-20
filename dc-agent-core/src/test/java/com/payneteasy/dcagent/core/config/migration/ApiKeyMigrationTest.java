package com.payneteasy.dcagent.core.config.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.payneteasy.dcagent.core.config.validation.ApiKeyHash;
import org.junit.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class ApiKeyMigrationTest {

    private static final Path SOURCE = Path.of("/cfg/site.json");

    private static JsonObject json(String aText) {
        return JsonParser.parseString(aText).getAsJsonObject();
    }

    @Test
    public void plaintext_keys_are_hashed_and_everything_else_is_kept_in_order() {
        JsonObject config = json("{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"plain-key\":\"ci\",\"" + ApiKeyHash.hash("other")
                + "\":\"ops\"},\"dir\":\"/opt/site\",\"n\":5}");

        ApiKeyMigration.Change change = ApiKeyMigration.migrate(SOURCE, config);

        assertThat(change.hashed()).isEqualTo(1);
        assertThat(change.shortKeys()).isEqualTo(1);
        assertThat(change.migrated().keySet()).containsExactly("type", "apiKeys", "dir", "n");
        assertThat(change.migrated().get("n").getAsInt()).isEqualTo(5);
        assertThat(change.migrated().getAsJsonObject("apiKeys").keySet())
                .containsExactly(ApiKeyHash.hash("plain-key"), ApiKeyHash.hash("other"));
    }

    @Test
    public void a_hashed_config_is_not_a_change_and_a_hash_is_never_hashed_again() {
        JsonObject hashed = json("{\"apiKeys\":{\"" + ApiKeyHash.hash("k") + "\":\"ci\"}}");

        assertThat(ApiKeyMigration.migrate(SOURCE, hashed)).isNull();
    }

    @Test
    public void the_same_key_in_plaintext_and_hashed_becomes_one_entry_with_the_first_owner() {
        JsonObject config = json("{\"apiKeys\":{\"k\":\"first\",\"" + ApiKeyHash.hash("k") + "\":\"second\"}}");

        JsonObject keys = ApiKeyMigration.migrate(SOURCE, config).migrated().getAsJsonObject("apiKeys");

        assertThat(keys.keySet()).containsExactly(ApiKeyHash.hash("k"));
        assertThat(keys.get(ApiKeyHash.hash("k")).getAsString()).isEqualTo("first");
    }

    @Test
    public void the_report_names_no_key() {
        JsonObject config = json("{\"apiKeys\":{\"secret-0123456789abcdef0123456789ab\":\"ci\"}}");

        assertThat(ApiKeyMigration.migrate(SOURCE, config).report())
                .isEqualTo("site.json: 1 plaintext key(s) hashed")
                .doesNotContain("secret");
    }
}
