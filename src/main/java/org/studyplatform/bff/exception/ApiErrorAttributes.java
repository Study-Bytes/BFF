package org.studyplatform.bff.exception;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> original = super.getErrorAttributes(
                webRequest,
                options.including(ErrorAttributeOptions.Include.MESSAGE)
        );

        int status = (int) original.getOrDefault("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        HttpStatus httpStatus = HttpStatus.resolve(status);
        String message = (String) original.getOrDefault(
                "message",
                httpStatus == null ? "Unexpected BFF error" : httpStatus.getReasonPhrase()
        );
        String requestId = webRequest.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("code", ErrorCodeMapper.codeFor(status));
        result.put("message", message);
        result.put("requestId", requestId);
        return result;
    }
}
