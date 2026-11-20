package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CommandConfigFileTest {

    private static final String SECRET = "s3cr3t-key-value: with colon and space";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path write(String aName, String aText) throws Exception {
        return Files.writeString(folder.getRoot().toPath().resolve(aName), aText, UTF_8);
    }

    @Test
    public void reads_json_and_yaml_into_the_same_object() throws Exception {
        JsonObject json = CommandConfigFile.read(write("a.json", "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/opt/a\"}"));
        JsonObject yaml = CommandConfigFile.read(write("b.yml", "type: ZIP_ARCHIVE\napiKeys:\n  k: ci\ndir: /opt/a\n"));

        assertThat(json).isEqualTo(yaml);
    }

    @Test
    public void two_api_keys_objects_are_refused_without_naming_a_key() throws Exception {
        // the first holds a plaintext key, the second only a hash: the parser would keep the second
        String text = "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + SECRET + "\":\"ci\"},\"apiKeys\":{\"sha256:00\":\"ci\"}}";

        assertThatThrownBy(() -> CommandConfigFile.read(write("a.json", text)))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("a.json: apiKeys: duplicate key")
                .message().doesNotContain("s3cr3t");
    }

    @Test
    public void a_key_repeated_inside_api_keys_is_refused_masked() throws Exception {
        String text = "{\"apiKeys\":{\"" + SECRET + "\":\"a\",\"" + SECRET + "\":\"b\"}}";

        assertThatThrownBy(() -> CommandConfigFile.read(write("a.json", text)))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("a.json: apiKeys.<key>: duplicate key");
    }

    @Test
    public void a_name_repeated_in_a_nested_object_is_refused() throws Exception {
        assertThatThrownBy(() -> CommandConfigFile.read(write("a.json", "{\"reloadHeaders\":{\"X\":\"1\",\"X\":\"2\"}}")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("a.json: reloadHeaders.X: duplicate key");
    }

    @Test
    public void yaml_duplicates_are_refused_and_api_keys_names_masked() throws Exception {
        assertThatThrownBy(() -> CommandConfigFile.read(write("a.yml", "apiKeys:\n  k: a\napiKeys:\n  j: b\n")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("a.yml: apiKeys: duplicate key");

        assertThatThrownBy(() -> CommandConfigFile.read(write("b.yml", "apiKeys:\n  '" + SECRET + "': a\n  '" + SECRET + "': b\n")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("b.yml: apiKeys.<key>: a duplicate or non-scalar key");
    }

    @Test
    public void invalid_json_message_does_not_quote_a_key() throws Exception {
        assertThatThrownBy(() -> CommandConfigFile.read(write("a.json", "{\"apiKeys\":{\"" + SECRET + "\" \"ci\"}}")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageStartingWith("a.json: not valid JSON")
                .message().doesNotContain("s3cr3t");
    }

    @Test
    public void not_an_object_and_trailing_text_are_refused() throws Exception {
        assertThatThrownBy(() -> CommandConfigFile.read(write("a.json", "Z2678PROBE ../dc-agent/config/zz2678probe.json")))
                .isInstanceOf(ConfigProblemException.class).hasMessageStartingWith("a.json: not");
        assertThatThrownBy(() -> CommandConfigFile.read(write("b.json", "[1]")))
                .isInstanceOf(ConfigProblemException.class).hasMessage("b.json: not a JSON object");
        assertThatThrownBy(() -> CommandConfigFile.read(write("c.json", "{} {}")))
                .isInstanceOf(ConfigProblemException.class).hasMessageStartingWith("c.json: ");
        assertThatThrownBy(() -> CommandConfigFile.read(write("d.yml", "- a\n- b\n")))
                .isInstanceOf(ConfigProblemException.class).hasMessage("d.yml: not a YAML mapping");
    }

    @Test
    public void a_symbolic_link_is_refused_a_regular_file_is_read() throws Exception {
        Path target = write("target", "{\"a\":\"b\"}");
        Path link   = folder.getRoot().toPath().resolve("link.json");
        Files.createSymbolicLink(link, target);
        Path copy   = write("copy.json", "{\"a\":\"b\"}");

        assertThat(CommandConfigFile.read(copy).get("a").getAsString()).isEqualTo("b");
        assertThatThrownBy(() -> CommandConfigFile.read(link))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("link.json: is a symbolic link; a config must be a regular file");
    }

    @Test
    public void not_utf8_and_too_large_are_refused() throws Exception {
        Path latin = folder.getRoot().toPath().resolve("a.json");
        Files.write(latin, new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xE9, '"', '}'});
        assertThatThrownBy(() -> CommandConfigFile.read(latin))
                .isInstanceOf(ConfigProblemException.class).hasMessage("a.json: is not UTF-8 text");

        Path large = write("b.json", "{\"a\":\"" + "x".repeat((int) CommandConfigFile.MAX_BYTES) + "\"}");
        assertThatThrownBy(() -> CommandConfigFile.read(large))
                .isInstanceOf(ConfigProblemException.class).hasMessageContaining("larger than");
    }

    @Test
    public void a_mistaken_api_keys_array_does_not_reveal_a_key() throws Exception {
        String json = "{\"apiKeys\":[{\"" + SECRET + "\":\"ci\",\"" + SECRET + "\":\"ci\"}]}";
        assertThatThrownBy(() -> CommandConfigFile.read(write("a.json", json)))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("a.json: apiKeys.<key>: duplicate key");

        String yaml = "apiKeys:\n  - '" + SECRET + "': ci\n    '" + SECRET + "': ci\n";
        assertThatThrownBy(() -> CommandConfigFile.read(write("b.yml", yaml)))
                .isInstanceOf(ConfigProblemException.class)
                .message().doesNotContain("s3cr3t");

        String broken = "{\"apiKeys\":[{\"" + SECRET + "\" \"ci\"}]}";
        assertThatThrownBy(() -> CommandConfigFile.read(write("c.json", broken)))
                .isInstanceOf(ConfigProblemException.class)
                .message().doesNotContain("s3cr3t");
    }

    @Test
    public void yaml_aliases_and_cycles_are_refused_not_a_stack_overflow() throws Exception {
        String base = "type: ZIP_ARCHIVE\napiKeys:\n  k: ci\ndir: /opt/a\n";
        assertThat(CommandConfigFile.read(write("ok.yml", base)).get("dir").getAsString()).isEqualTo("/opt/a");

        assertThatThrownBy(() -> CommandConfigFile.read(write("cycle.yml", base + "ignored: &a {cycle: *a}\n")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("cycle.yml: YAML anchors and aliases are not supported in a config");
        assertThatThrownBy(() -> CommandConfigFile.read(write("alias.yml", base + "x: &v one\ny: *v\n")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageContaining("aliases");
    }

    @Test
    public void deeply_nested_yaml_is_refused() throws Exception {
        int    depth = 200_000;
        String yaml  = "a: " + "[".repeat(depth) + "]".repeat(depth) + "\n";

        assertThatThrownBy(() -> CommandConfigFile.read(write("deep.yml", yaml)))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageStartingWith("deep.yml: YAML nested");
        assertThatThrownBy(() -> CommandConfigFile.read(write("deep2.yml", "a: " + "[".repeat(70) + "]".repeat(70) + "\n")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessage("deep2.yml: YAML nested deeper than " + CommandConfigFile.MAX_YAML_DEPTH + " levels");
    }

    @Test
    public void deeply_nested_json_is_refused() throws Exception {
        int depth = 200_000;
        assertThatThrownBy(() -> CommandConfigFile.read(write("deep.json", "{\"a\":" + "[".repeat(depth) + "]".repeat(depth) + "}")))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageStartingWith("deep.json: not valid JSON");
    }
}
