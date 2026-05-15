package org.studyplatform.bff.exception;

public record ApiErrorResponse(
        String timestamp,
        int status,
        String error,
        String code,
        String message,
        String path
) {
}
