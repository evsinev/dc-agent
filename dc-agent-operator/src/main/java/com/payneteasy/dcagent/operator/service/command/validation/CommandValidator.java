package com.payneteasy.dcagent.operator.service.command.validation;

import com.payneteasy.dcagent.core.config.CommandNames;
import com.payneteasy.mini.core.error.exception.ApiBadRequestErrorException;
import com.payneteasy.mini.core.error.model.BadRequestError;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side validation mirroring the client. Field-level rules (absolute paths, URLs, …) are
 * enforced in the browser; the security-relevant checks — a safe command name and traversal
 * defense — are re-checked here ({@link CommandNames}, the same rule the agent applies on write and
 * on call; plus {@code SafeFiles.createFileGuarded} on the agent).
 */
public class CommandValidator {

    private CommandValidator() {
    }

    public static void validateName(String aName) {
        List<BadRequestError.InvalidParam> invalid = new ArrayList<>();
        if (aName == null || aName.trim().isEmpty()) {
            invalid.add(param("name", "Name is required."));
        } else if (!CommandNames.isValid(aName)) {
            invalid.add(param("name", "Only letters, digits, . _ - are allowed."));
        }
        if (!invalid.isEmpty()) {
            throw new ApiBadRequestErrorException(BadRequestError.builder()
                    .errorMessage("Invalid command")
                    .errorCorrelationId(UUID.randomUUID().toString())
                    .invalidParams(invalid)
                    .build());
        }
    }

    private static BadRequestError.InvalidParam param(String aName, String aReason) {
        return BadRequestError.InvalidParam.builder().name(aName).reason(aReason).build();
    }
}
