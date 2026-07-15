package com.payneteasy.dcagent.core.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PathParametersTest {

    @Test
    public void splits_uri_into_segments_ignoring_slashes() {
        PathParameters params = new PathParameters("/docker/push/java-app");

        assertThat(params.getParams()).containsExactly("docker", "push", "java-app");
    }

    @Test
    public void get_last_returns_last_segment() {
        assertThat(new PathParameters("/a/b/c").getLast()).isEqualTo("c");
    }

    @Test
    public void get_last_but_one_returns_second_to_last_segment() {
        assertThat(new PathParameters("/a/b/c").getLastButOne()).isEqualTo("b");
    }

    @Test
    public void get_second_from_last_returns_third_to_last_segment() {
        assertThat(new PathParameters("/a/b/c").getSecondFromLast()).isEqualTo("a");
    }

    @Test
    public void out_of_range_position_throws_illegal_state() {
        PathParameters params = new PathParameters("/only");

        assertThatThrownBy(() -> params.getFromLast(5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void get_params_returns_immutable_copy() {
        PathParameters params = new PathParameters("/a/b");

        assertThatThrownBy(() -> params.getParams().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
