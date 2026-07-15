package com.payneteasy.dcagent.core.exception;

import org.junit.Test;

import static com.payneteasy.dcagent.core.exception.HttpProblemBuilder.problem;
import static org.assertj.core.api.Assertions.assertThat;

public class HttpProblemBuilderTest {

    @Test
    public void copies_type_metadata_from_the_problem_type() {
        HttpProblem problem = problem(DcProblem.FILE_NOT_FOUND).exception().getProblem();

        assertThat(problem.getTitle()).isEqualTo("File not found");
        assertThat(problem.getType()).isEqualTo("FILE_NOT_FOUND");
        assertThat(problem.getStatus()).isEqualTo(500);
    }

    @Test
    public void carries_detail_and_memo() {
        HttpProblem problem = problem(DcProblem.UNKNOWN)
                .detail("bad thing")
                .memo("internal note")
                .exception()
                .getProblem();

        assertThat(problem.getDetail()).isEqualTo("bad thing");
        assertThat(problem.getMemo()).isEqualTo("internal note");
    }

    @Test
    public void carries_context_and_env_entries() {
        HttpProblem problem = problem(DcProblem.UNKNOWN)
                .context("key", "value")
                .env("host", "srv-1")
                .exception()
                .getProblem();

        assertThat(problem.getContext()).containsEntry("key", "value");
        assertThat(problem.getEnv()).containsEntry("host", "srv-1");
    }

    @Test
    public void exception_without_cause_has_null_cause() {
        assertThat(problem(DcProblem.UNKNOWN).exception().getCause()).isNull();
    }

    @Test
    public void exception_with_cause_preserves_it() {
        Exception cause = new RuntimeException("boom");

        HttpProblemException ex = problem(DcProblem.UNKNOWN).exception(cause);

        assertThat(ex.getCause()).isSameAs(cause);
    }
}
