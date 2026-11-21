package com.payneteasy.dcagent.core.config.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.payneteasy.dcagent.core.config.validation.ApiKeyHash;
import com.payneteasy.dcagent.core.config.validation.CommandConfigFile;
import com.payneteasy.dcagent.core.config.validation.ConfigCheckMode;
import com.payneteasy.dcagent.core.config.validation.TestValidators;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class KeysMigrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Path SOURCE = Path.of("/cfg/site.json");

    private static JsonObject json(String aText) {
        return JsonParser.parseString(aText).getAsJsonObject();
    }

    @Test
    public void api_keys_become_sha256_keys_in_place_and_the_result_is_valid() {
        String a = ApiKeyHash.hash("first-key-0123456789abcdef0123456789");
        String b = ApiKeyHash.hash("second-key-0123456789abcdef012345678");
        JsonObject config = json("{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + a + "\":\"gitlab-ci\",\"" + b + "\":\"gitlab-ci\"},\"dir\":\"/opt/site\"}");

        KeysMigration.Change change = KeysMigration.migrate(SOURCE, config);

        assertThat(change.problem()).isNull();
        assertThat(change.keys()).isEqualTo(2);
        assertThat(change.migrated().keySet()).containsExactly("type", "keys", "dir");
        JsonArray keys = change.migrated().getAsJsonArray("keys");
        assertThat(keys.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("gitlab-ci");
        assertThat(keys.get(1).getAsJsonObject().get("name").getAsString()).isEqualTo("gitlab-ci-2");
        assertThat(keys.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("SHA256");
        assertThat(keys.get(1).getAsJsonObject().get("hash").getAsString()).isEqualTo(b);
        assertThat(TestValidators.validator(Gsons.PRETTY_GSON).validate("site", change.migrated(), ConfigCheckMode.STRICT)).isEmpty();
    }

    @Test
    public void a_plaintext_key_is_not_migrated() {
        JsonObject config = json("{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"plain-key-0123456789abcdef012345678\":\"ci\"},\"dir\":\"/opt/site\"}");

        KeysMigration.Change change = KeysMigration.migrate(SOURCE, config);

        assertThat(change.problem()).contains("plaintext");
        assertThat(change.migrated()).isNull();
        assertThat(change.report()).doesNotContain("plain-key");
    }

    @Test
    public void a_config_on_keys_or_without_keys_is_not_a_change() {
        assertThat(KeysMigration.migrate(SOURCE, json("{\"type\":\"ZIP_ARCHIVE\",\"keys\":[]}"))).isNull();
        assertThat(KeysMigration.migrate(SOURCE, json("{\"type\":\"ZIP_ARCHIVE\"}"))).isNull();
    }

    @Test
    public void owner_labels_become_valid_unique_names() {
        Set<String> taken = new HashSet<>();
        assertThat(KeysMigration.uniqueName("gitlab ci/deploy", taken)).isEqualTo("gitlab-ci-deploy");
        assertThat(KeysMigration.uniqueName("gitlab ci/deploy", taken)).isEqualTo("gitlab-ci-deploy-2");
        assertThat(KeysMigration.uniqueName("", taken)).isEqualTo("key");
    }

    @Test
    public void apply_rewrites_json_in_place_and_yaml_as_json() throws Exception {
        Path dir = tmp.newFolder("config").toPath();
        String hash = ApiKeyHash.hash("first-key-0123456789abcdef0123456789");
        Files.writeString(dir.resolve("site.json"), "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + hash + "\":\"ci\"},\"dir\":\"/opt/site\"}", UTF_8);
        Files.writeString(dir.resolve("menu.yml"), "type: ZIP_ARCHIVE\napiKeys:\n  \"" + hash + "\": ci\ndir: /opt/menu\n", UTF_8);

        KeysMigration              migration = new KeysMigration();
        List<KeysMigration.Change> changes   = migration.plan(dir);
        migration.apply(dir, changes);

        assertThat(changes).hasSize(2);
        assertThat(Files.exists(dir.resolve("menu.yml"))).isFalse();
        for (String name : new String[]{"site.json", "menu.json"}) {
            JsonObject written = CommandConfigFile.read(dir.resolve(name));
            assertThat(written.has("apiKeys")).isFalse();
            assertThat(written.getAsJsonArray("keys").get(0).getAsJsonObject().get("hash").getAsString()).isEqualTo(hash);
        }
        assertThat(migration.plan(dir)).isEmpty();
    }

    @Test
    public void a_json_and_yaml_pair_is_refused_and_nothing_is_written() throws Exception {
        Path dir = tmp.newFolder("pair").toPath();
        String hash = ApiKeyHash.hash("first-key-0123456789abcdef0123456789");
        String json = "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/opt/site\",\"keys\":[{\"name\":\"ci\",\"type\":\"SHA256\",\"hash\":\"" + hash + "\"}]}";
        Files.writeString(dir.resolve("site.json"), json, UTF_8);
        Files.writeString(dir.resolve("site.yml"), "type: ZIP_ARCHIVE\napiKeys:\n  \"" + hash + "\": ci\ndir: /opt/site\n", UTF_8);

        KeysMigration              migration = new KeysMigration();
        List<KeysMigration.Change> changes   = migration.plan(dir);
        migration.apply(dir, changes);

        assertThat(changes).singleElement().satisfies(change -> assertThat(change.problem()).contains("both exist"));
        assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(json);
        assertThat(Files.exists(dir.resolve("site.yml"))).isTrue();
    }

    @Test
    public void a_result_larger_than_the_agent_reads_is_refused() throws Exception {
        Path dir = tmp.newFolder("big").toPath();
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            keys.append(i == 0 ? "" : ",").append('"').append(ApiKeyHash.hash("key-" + i + "-0123456789abcdef0123456789")).append("\":\"ci\"");
        }
        Files.writeString(dir.resolve("site.json"), "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/opt/site\",\"apiKeys\":{" + keys + "}}", UTF_8);
        assertThat(Files.size(dir.resolve("site.json"))).isLessThan(CommandConfigFile.MAX_BYTES);

        assertThat(new KeysMigration().plan(dir)).singleElement().satisfies(change -> assertThat(change.problem()).contains("larger than"));
    }

    @Test
    public void a_non_string_owner_is_not_migrated_into_a_valid_key() {
        JsonObject config = json("{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + ApiKeyHash.hash("first-key-0123456789abcdef0123456789") + "\":null},\"dir\":\"/opt/site\"}");

        KeysMigration.Change change = KeysMigration.migrate(SOURCE, config);

        assertThat(change.problem()).contains("owner is not a string");
        assertThat(change.migrated()).isNull();
    }
}
