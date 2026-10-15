package com.payneteasy.dcagent.operator.validation;

import com.payneteasy.mini.core.error.exception.ApiBadRequestErrorException;
import com.payneteasy.mini.core.error.handler.ApiRequestValidator;
import com.payneteasy.mini.core.error.model.BadRequestError;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Smoke test for the bean validation stack (hibernate-validator + Jakarta EL implementation)
 * that mini-core's {@link ApiRequestValidator} uses for incoming API requests.
 */
public class ApiRequestValidatorTest {

    static class Request {
        @NotNull(message = "name is required")
        String name;

        @Size(max = 3, message = "size must be at most {max}, got '${validatedValue}'")
        String code;

        Request(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    static class TagsRequest {
        @UniqueElements(message = "duplicates: {duplicates}")
        List<String> tags;

        TagsRequest(List<String> tags) {
            this.tags = tags;
        }
    }

    private final ApiRequestValidator validator = new ApiRequestValidator();

    @Test
    public void valid_request_passes() {
        Request request = new Request("app", "abc");
        assertThat(validator.validateRequest(request, Request.class)).isSameAs(request);
    }

    @Test
    public void invalid_request_reports_interpolated_messages() {
        ApiBadRequestErrorException e = catchThrowableOfType(
                ApiBadRequestErrorException.class
                , () -> validator.validateRequest(new Request(null, "abcdef"), Request.class)
        );

        assertThat(e).isNotNull();
        assertThat(e.getBadRequestError().getInvalidParams())
                .extracting(BadRequestError.InvalidParam::getName, BadRequestError.InvalidParam::getReason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("name", "name is required")
                        , org.assertj.core.groups.Tuple.tuple("code", "size must be at most 3, got 'abcdef'")
                );
    }

    /**
     * CVE-2026-93879 / HV-2256: user input passed as a message parameter ({duplicates})
     * must not be re-evaluated as an EL expression.
     */
    @Test
    public void message_parameters_are_not_evaluated_as_el() {
        ApiBadRequestErrorException e = catchThrowableOfType(
                ApiBadRequestErrorException.class
                , () -> validator.validateRequest(new TagsRequest(List.of("${1+1}", "${1+1}")), TagsRequest.class)
        );

        assertThat(e).isNotNull();
        assertThat(e.getBadRequestError().getInvalidParams())
                .extracting(BadRequestError.InvalidParam::getReason)
                .containsExactly("duplicates: ${1+1}");
    }
}
