package org.studyplatform.bff.exception;

public record ApiValidationError(
        String field,
        String message
) {
}
