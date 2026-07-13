package com.payneteasy.dcagent.operator.service.command.error;

import com.payneteasy.mini.core.error.model.IError;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

import static lombok.AccessLevel.PRIVATE;

/**
 * Carries an explicit HTTP status for command create/edit failures (409 conflict, 404 not found,
 * 502 agent unreachable). Thrown via {@code ApiErrorException}; the framework's ApiExceptionHandler
 * writes it as JSON with {@link #getHttpReasonCode()} as the response status.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandApiError implements IError {

    String errorCorrelationId;
    String errorMessage;
    int    httpReasonCode;

    public static CommandApiError of(int aHttpReasonCode, String aMessage) {
        return CommandApiError.builder()
                .httpReasonCode(aHttpReasonCode)
                .errorMessage(aMessage)
                .errorCorrelationId(UUID.randomUUID().toString())
                .build();
    }
}
