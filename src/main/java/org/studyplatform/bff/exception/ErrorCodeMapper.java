package org.studyplatform.bff.exception;

public final class ErrorCodeMapper {

    private ErrorCodeMapper() {
    }

    public static String codeFor(int status) {
        return switch (status) {
            case 400 -> "VALIDATION_ERROR";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_SERVER_ERROR" : "VALIDATION_ERROR";
        };
    }
}
