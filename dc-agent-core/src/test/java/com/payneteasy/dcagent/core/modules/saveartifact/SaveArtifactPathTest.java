package com.payneteasy.dcagent.core.modules.saveartifact;

import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code version} and the extension header come from the request: the file must stay inside dir. */
public class SaveArtifactPathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dir;
    private Path outside;

    @Before
    public void setUp() throws IOException {
        Path base = tmp.getRoot().toPath().toRealPath();
        dir     = Files.createDirectory(base.resolve("artifacts"));
        outside = Files.createDirectory(base.resolve("outside"));
    }

    @Test
    public void stores_version_dot_configured_extension_in_dir() {
        File file = SaveArtifactPath.resolve(config(null), "master-216018", null);

        assertThat(file.toPath()).isEqualTo(dir.resolve("master-216018.apk"));
    }

    @Test
    public void replace_dir_chars_makes_sub_directories_inside_dir() {
        File file = SaveArtifactPath.resolve(config("__"), "release__1.2.3", null);

        assertThat(file.toPath()).isEqualTo(dir.resolve("release/1.2.3.apk"));
    }

    @Test
    public void uses_a_plain_extension_from_the_header() {
        assertThat(SaveArtifactPath.resolve(config(null), "1.0", "tar.gz").toPath()).isEqualTo(dir.resolve("1.0.tar.gz"));
        assertThat(SaveArtifactPath.resolve(config(null), "1.0", "jar").toPath()).isEqualTo(dir.resolve("1.0.jar"));
    }

    @Test
    public void refuses_a_header_extension_that_is_a_path() {
        for (String extension : new String[]{"x/../../../outside/evil", "../evil", "a/b", "a\\b", ".hidden", "a..b", "-rf"}) {
            assertThatThrownBy(() -> SaveArtifactPath.resolve(config(null), "1.0", extension))
                    .as(extension)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x-dc-agent-file-extension");
        }
        assertThat(outside.toFile().list()).isEmpty();
    }

    @Test
    public void refuses_dot_dot_in_the_version() {
        assertThatThrownBy(() -> SaveArtifactPath.resolve(config("__"), "..__..__outside__evil", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void refuses_a_path_that_leaves_dir_through_a_link() throws IOException {
        Files.createSymbolicLink(dir.resolve("link"), outside);

        assertThatThrownBy(() -> SaveArtifactPath.resolve(config("__"), "link__evil", null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    public void a_blank_or_missing_header_falls_back_to_the_configured_extension() {
        assertThat(SaveArtifactPath.resolve(config(null), "1.0", null).toPath()).isEqualTo(dir.resolve("1.0.apk"));
        assertThat(SaveArtifactPath.resolve(config(null), "1.0", "").toPath()).isEqualTo(dir.resolve("1.0.apk"));
        assertThat(SaveArtifactPath.resolve(config(null), "1.0", "  ").toPath()).isEqualTo(dir.resolve("1.0.apk"));
    }

    @Test
    public void does_not_write_through_a_dangling_link_at_the_file_place() throws IOException {
        // dir/v.jar -> outside/newjob (not there yet): the canonical check keeps dir/v.jar
        Path target = outside.resolve("newjob");
        Files.createSymbolicLink(dir.resolve("v.jar"), target);
        File file = SaveArtifactPath.resolve(config(null), "v", "jar");

        assertThatThrownBy(() -> SaveArtifactPath.write(file, new ByteArrayInputStream("evil".getBytes())))
                .isInstanceOf(IOException.class);

        assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    public void writes_and_overwrites_a_regular_file() throws IOException {
        File file = SaveArtifactPath.resolve(config(null), "1.0", null);

        SaveArtifactPath.write(file, new ByteArrayInputStream("first".getBytes()));
        SaveArtifactPath.write(file, new ByteArrayInputStream("2".getBytes()));

        assertThat(file).hasContent("2");
    }

    private TSaveArtifactConfig config(String aReplaceDirChars) {
        return TSaveArtifactConfig.builder()
                .dir(dir.toString())
                .extension("apk")
                .replaceDirChars(aReplaceDirChars)
                .build();
    }
}
