package org.studyplatform.bff.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "org.studyplatform.bff")
public class GlobalErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorVm handleRemoteCallFailure(ResponseStatusException ex) {
        return new ErrorVm("AUTHENTICATION_SERVICE_ERROR", ex.getReason());
    }

    public record ErrorVm(String code, String message) { }
}
