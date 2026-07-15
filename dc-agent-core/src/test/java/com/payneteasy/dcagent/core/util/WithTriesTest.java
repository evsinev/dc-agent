package com.payneteasy.dcagent.core.util;

import com.payneteasy.dcagent.core.exception.DcProblem;
import com.payneteasy.dcagent.core.exception.HttpProblemException;
import org.junit.Test;

import static com.payneteasy.dcagent.core.util.WithTries.tryCall;
import static com.payneteasy.dcagent.core.util.WithTries.withProblem;
import static com.payneteasy.dcagent.core.util.WithTries.withTry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class WithTriesTest {

    @Test
    public void with_problem_returns_the_value_on_success() {
        assertThat(withProblem(() -> "ok", DcProblem.UNKNOWN)).isEqualTo("ok");
    }

    @Test
    public void with_problem_wraps_failure_as_http_problem() {
        assertThatThrownBy(() -> withProblem(() -> { throw new RuntimeException("boom"); }, DcProblem.UNKNOWN))
                .isInstanceOf(HttpProblemException.class);
    }

    @Test
    public void with_try_returns_the_value_on_success() {
        assertThat(withTry(() -> 42, "should not fail")).isEqualTo(42);
    }

    @Test
    public void with_try_wraps_failure_as_illegal_state_with_message() {
        assertThatThrownBy(() -> withTry(() -> { throw new RuntimeException("x"); }, "my message"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("my message");
    }

    @Test
    public void try_call_runs_the_action_on_success() {
        int[] counter = {0};

        tryCall(() -> counter[0]++, "should not fail");

        assertThat(counter[0]).isEqualTo(1);
    }

    @Test
    public void try_call_wraps_failure_as_illegal_state() {
        assertThatThrownBy(() -> tryCall(() -> { throw new RuntimeException("x"); }, "boom"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }
}
