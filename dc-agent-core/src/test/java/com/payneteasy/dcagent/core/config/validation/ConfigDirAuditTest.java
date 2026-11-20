package com.payneteasy.dcagent.core.config.validation;

import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigDirAuditTest {

    private static final String GOOD = "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/opt/site\"}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final ConfigDirAudit audit = new ConfigDirAudit(TestValidators.validator(Gsons.PRETTY_GSON));

    private Path dir() {
        return folder.getRoot().toPath();
    }

    private void write(String aName, String aText) throws Exception {
        Files.writeString(dir().resolve(aName), aText, UTF_8);
    }

    @Test
    public void a_good_directory_has_no_problems_and_other_files_are_ignored() throws Exception {
        write("site.json", GOOD);
        write("menu.yml", "type: ZIP_ARCHIVE\napiKeys:\n  k: ci\ndir: /opt/menu\n");
        write("README", "not a config");
        write(".site.json.tmp-1234", "{");

        assertThat(audit.audit(dir())).isEmpty();
    }

    @Test
    public void the_paynet_react_leftovers_are_all_reported() throws Exception {
        write("assets.json", GOOD);
        write("i4153.json", "{\"apiKeys\":{\"i4153key\":\"i4153\"},\"dir\":\"/root\"}");
        write("zz2678probe.json", "Z2678PROBE ../dc-agent/config/zz2678probe.json @2026-11-19 by i2678");

        assertThat(audit.audit(dir()))
                .hasSize(2)
                .anySatisfy(problem -> assertThat(problem).startsWith("i4153.json: no \"type\""))
                .anySatisfy(problem -> assertThat(problem).startsWith("zz2678probe.json: not valid JSON"));
    }

    @Test
    public void json_and_yaml_of_one_command_are_a_problem() throws Exception {
        write("site.json", GOOD);
        write("site.yml", "type: ZIP_ARCHIVE\napiKeys:\n  k: ci\ndir: /opt/site\n");

        assertThat(audit.audit(dir())).containsExactly("site.json and site.yml both exist; keep one");
    }

    @Test
    public void a_symbolic_link_config_is_a_problem() throws Exception {
        write("source", GOOD);
        Files.createSymbolicLink(dir().resolve("site.json"), dir().resolve("source"));

        assertThat(audit.audit(dir())).containsExactly("site.json: is a symbolic link; a config must be a regular file");
    }

    @Test
    public void a_missing_directory_or_a_file_is_a_problem_not_an_empty_directory() throws Exception {
        write("file", "x");

        assertThat(audit.audit(dir().resolve("absent"))).singleElement().asString().endsWith("does not exist");
        assertThat(audit.audit(dir().resolve("file"))).singleElement().asString().endsWith("is not a directory");
    }
}
