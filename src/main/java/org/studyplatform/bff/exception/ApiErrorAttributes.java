package org.studyplatform.bff.exception;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(
                webRequest,
                options.including(ErrorAttributeOptions.Include.MESSAGE)
        );

        int status = (int) attributes.getOrDefault("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        HttpStatus httpStatus = HttpStatus.resolve(status);
        attributes.put("code", codeFor(status));
        attributes.putIfAbsent(
                "message",
                httpStatus == null ? "Unexpected BFF error" : httpStatus.getReasonPhrase()
        );

        return attributes;
    }

    private String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 502 -> "UPSTREAM_SERVICE_ERROR";
            default -> status >= 500 ? "INTERNAL_SERVER_ERROR" : "BFF_REQUEST_ERROR";
        };
    }
}
