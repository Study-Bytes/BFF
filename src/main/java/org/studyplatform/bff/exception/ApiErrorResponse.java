package org.studyplatform.bff.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        int status,
        String code,
        String message,
        String requestId,
        List<ApiValidationError> validationErrors
) {
}
