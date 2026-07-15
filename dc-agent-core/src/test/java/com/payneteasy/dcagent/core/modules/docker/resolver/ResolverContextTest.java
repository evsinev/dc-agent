package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.DockerDirectories;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ResolverContextTest {

    private static final DockerDirectories DIRS = DockerDirectories.builder()
            .destinationBaseDir("/opt/dest")
            .sourceBaseDir("/opt/src")
            .build();

    private static final File UPLOADED = new File("/uploaded");

    private ResolverContext context(String source, String destination) {
        return new ResolverContext(DIRS, UPLOADED, source, destination, null, null, null);
    }

    @Test
    public void full_destination_keeps_absolute_path() {
        assertThat(context(null, "/opt/app/conf").fullDestination()).isEqualTo(new File("/opt/app/conf"));
    }

    @Test
    public void full_destination_resolves_relative_against_base_dir() {
        assertThat(context(null, "./conf/app.yml").fullDestination())
                .isEqualTo(new File("/opt/dest/conf/app.yml"));
    }

    @Test
    public void full_destination_falls_back_to_source_when_destination_null() {
        assertThat(context("/opt/only-source", null).fullDestination()).isEqualTo(new File("/opt/only-source"));
    }

    @Test
    public void full_source_keeps_absolute_path() {
        assertThat(context("/opt/app/file", "/dst").fullSource()).isEqualTo(new File("/opt/app/file"));
    }

    @Test
    public void full_source_resolves_relative_against_base_dir() {
        assertThat(context("./versions/app.jar", "/dst").fullSource())
                .isEqualTo(new File("/opt/src/versions/app.jar"));
    }

    @Test
    public void full_source_uses_destination_base_when_source_empty() {
        assertThat(context("", "relative/path").fullSource()).isEqualTo(new File("/opt/src/relative/path"));
    }

    @Test
    public void full_config_uses_explicit_config_path() {
        assertThat(context("/opt/x", "/dst").fullConfig("my-config")).isEqualTo(new File("/uploaded/my-config"));
    }

    @Test
    public void full_config_without_path_uses_source_file_name() {
        assertThat(context("/opt/some/app.jar", "/dst").fullConfig(""))
                .isEqualTo(new File("/uploaded/app.jar"));
    }

    @Test
    public void relative_destination_without_directories_throws() {
        ResolverContext context = new ResolverContext(null, UPLOADED, null, "relative/x", null, null, null);

        assertThatThrownBy(context::fullDestination).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void empty_source_base_dir_throws() {
        DockerDirectories noSrc = DockerDirectories.builder().destinationBaseDir("/opt/dest").build();
        ResolverContext context = new ResolverContext(noSrc, UPLOADED, "relative/x", "/dst", null, null, null);

        assertThatThrownBy(context::fullSource).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void null_bound_variables_become_empty_list() {
        assertThat(context("/a", "/b").getResolvedBoundVariables()).isEmpty();
    }

    @Test
    public void bound_variables_are_returned_as_immutable_copy() {
        BoundVariable var = BoundVariable.builder().name("K").value("v").build();
        ResolverContext context = new ResolverContext(DIRS, UPLOADED, "/a", "/b", null, null, List.of(var));

        assertThatThrownBy(() -> context.getResolvedBoundVariables().add(var))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
