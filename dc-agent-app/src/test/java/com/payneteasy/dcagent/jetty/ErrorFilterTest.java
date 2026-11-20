package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.exception.Problem;
import com.payneteasy.dcagent.core.exception.ProblemException;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a caller sees: a ProblemException's own text; for anything else the error id only. */
public class ErrorFilterTest {

    @Test
    public void a_problem_goes_out_as_written() {
        Problem problem = ErrorFilter.problem(new ProblemException(413, "the upload exceeds maxUploadBytes (10 bytes)"), "id-1");

        assertThat(problem.getTitle()).isEqualTo("the upload exceeds maxUploadBytes (10 bytes)");
        assertThat(problem.getErrorId()).isEqualTo("id-1");
    }

    @Test
    public void an_unexpected_error_does_not_leak_its_text() {
        Problem problem = ErrorFilter.problem(
                new IllegalStateException("Refusing to delete /srv/www/app/../../etc/x outside of /srv/www/app"), "id-2");

        assertThat(problem.getType()).isEqualTo("InternalError");
        assertThat(problem.getTitle()).isEqualTo("Internal error, see the agent log for id-2")
                .doesNotContain("/srv").doesNotContain("Refusing");
        assertThat(problem.getErrorId()).isEqualTo("id-2");
    }
}
