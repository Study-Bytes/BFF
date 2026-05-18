package org.studyplatform.bff.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.config.AuthCookieProperties;
import org.studyplatform.bff.exception.ApiErrorResponse;
import org.studyplatform.bff.exception.ApiValidationError;
import org.studyplatform.bff.exception.ErrorCodeMapper;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProxyExchangeService {
    private static final Logger log = LoggerFactory.getLogger(ProxyExchangeService.class);

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            HttpHeaders.HOST,
            HttpHeaders.CONTENT_LENGTH
    );

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            HttpHeaders.TRANSFER_ENCODING,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.CONNECTION
    );

    private final ObjectMapper objectMapper;
    private final AuthCookieProperties authCookieProperties;
    private final Duration defaultUpstreamTimeout;
    private final Duration learningRunSubmitTimeout;

    public ProxyExchangeService(
            ObjectMapper objectMapper,
            AuthCookieProperties authCookieProperties,
            @Value("${bff.proxy.timeout-seconds:5}") long defaultUpstreamTimeoutSeconds,
            @Value("${bff.proxy.learning-run-submit-timeout-seconds:60}") long learningRunSubmitTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.authCookieProperties = authCookieProperties;
        this.defaultUpstreamTimeout = Duration.ofSeconds(defaultUpstreamTimeoutSeconds);
        this.learningRunSubmitTimeout = Duration.ofSeconds(learningRunSubmitTimeoutSeconds);
    }

    public ResponseEntity<byte[]> exchange(HttpServletRequest request, WebClient client, String upstreamUri) {
        return exchange(request, client, HttpMethod.valueOf(request.getMethod()), upstreamUri, null, Map.of());
    }

    public ResponseEntity<byte[]> exchange(
            HttpServletRequest request,
            WebClient client,
            String upstreamUri,
            byte[] requestBodyOverride,
            Map<String, String> headerOverrides
    ) {
        return exchange(
                request,
                client,
                HttpMethod.valueOf(request.getMethod()),
                upstreamUri,
                requestBodyOverride,
                headerOverrides
        );
    }

    public ResponseEntity<byte[]> exchange(
            HttpServletRequest request,
            WebClient client,
            HttpMethod method,
            String upstreamUri,
            byte[] requestBodyOverride,
            Map<String, String> headerOverrides
    ) {
        byte[] requestBody;
        try {
            requestBody = requestBodyOverride != null ? requestBodyOverride : readBody(request);
        } catch (IOException ex) {
            String message = isLearningRunSubmit(method, upstreamUri)
                    ? "Invalid JSON body"
                    : "Cannot read request body";
            return errorResponse(
                    HttpStatus.BAD_REQUEST,
                    message,
                    resolveRequestId(request.getHeader("X-Request-Id")),
                    null
            );
        }

        WebClient.RequestHeadersSpec<?> spec = client
                .method(method)
                .uri(upstreamUri)
                .headers(headers -> copyRequestHeaders(request, headers, headerOverrides, method, upstreamUri));

        if (requestBody.length > 0 && methodAllowsBody(method.name())) {
            spec = ((WebClient.RequestBodySpec) spec).bodyValue(requestBody);
        }

        String requestId = resolveRequestId(request.getHeader("X-Request-Id"));
        Duration timeout = resolveTimeout(method, upstreamUri);
        boolean learningRunSubmit = isLearningRunSubmit(method, upstreamUri);
        long startedAt = System.currentTimeMillis();
        if (learningRunSubmit) {
            log.debug(
                    "BFF proxy request requestId={} method={} path={} upstream={} contentType={} bodyBytes={} authPresent={}",
                    requestId,
                    method.name(),
                    request.getRequestURI(),
                    upstreamUri,
                    request.getContentType(),
                    requestBody.length,
                    hasAuthorizationHeader(request)
            );
        }
        return spec.exchangeToMono(response -> response.toEntity(byte[].class))
                .map(upstream -> {
                    if (learningRunSubmit) {
                        int bodyBytes = upstream.getBody() == null ? 0 : upstream.getBody().length;
                        long durationMs = System.currentTimeMillis() - startedAt;
                        log.debug(
                                "BFF proxy response requestId={} method={} upstream={} status={} durationMs={} bodyBytes={}",
                                requestId,
                                method.name(),
                                upstreamUri,
                                upstream.getStatusCode().value(),
                                durationMs,
                                bodyBytes
                        );
                    }
                    return normalizeResponse(upstream, requestId);
                })
                .timeout(timeout)
                .doOnError(ex -> {
                    if (learningRunSubmit) {
                        long durationMs = System.currentTimeMillis() - startedAt;
                        log.warn(
                                "BFF proxy error requestId={} method={} upstream={} durationMs={} reason={}",
                                requestId,
                                method.name(),
                                upstreamUri,
                                durationMs,
                                ex.getClass().getSimpleName()
                        );
                    }
                })
                .onErrorResume(ex -> Mono.just(errorResponse(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Upstream service is unavailable or returned an invalid response",
                        requestId,
                        null
                )))
                .block();
    }

    private Duration resolveTimeout(HttpMethod method, String upstreamUri) {
        if (isLearningRunSubmit(method, upstreamUri)) {
            return learningRunSubmitTimeout;
        }
        return defaultUpstreamTimeout;
    }

    private boolean isLearningRunSubmit(HttpMethod method, String upstreamUri) {
        return method == HttpMethod.POST
                && upstreamUri.startsWith("/api/v1/learn/courses/")
                && upstreamUri.contains("/items/")
                && (upstreamUri.endsWith("/run") || upstreamUri.endsWith("/submit"));
    }

    public String buildUpstreamUri(HttpServletRequest request, String prefixToRemove) {
        String pathAfterPrefix = request.getRequestURI().replaceFirst(prefixToRemove, "");
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        return "/api/v1" + pathAfterPrefix + query;
    }

    public String buildAuthOrUsersUri(HttpServletRequest request) {
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        return request.getRequestURI() + query;
    }

    public String buildCurrentUserUri(HttpServletRequest request) {
        String path = request.getRequestURI();
        String upstreamPath;
        if ("/api/v1/me".equals(path)) {
            upstreamPath = "/api/v1/users/me";
        } else if (path.startsWith("/api/v1/me/")) {
            upstreamPath = "/api/v1/users/me" + path.substring("/api/v1/me".length());
        } else {
            upstreamPath = path;
        }
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        return upstreamPath + query;
    }

    private ResponseEntity<byte[]> normalizeResponse(ResponseEntity<byte[]> upstream, String requestId) {
        if (!upstream.getStatusCode().isError()) {
            return withoutUnsafeResponseHeaders(upstream);
        }

        HttpStatus status = HttpStatus.resolve(upstream.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.GATEWAY_TIMEOUT) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }

        ParsedUpstreamError upstreamError = parseUpstreamError(upstream.getBody(), status);
        ApiErrorResponse response = new ApiErrorResponse(
                status.value(),
                ErrorCodeMapper.codeFor(status.value()),
                upstreamError.message(),
                requestId,
                upstreamError.validationErrors().isEmpty() ? null : upstreamError.validationErrors()
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(response));
    }

    private ParsedUpstreamError parseUpstreamError(byte[] body, HttpStatus status) {
        if (body == null || body.length == 0) {
            return new ParsedUpstreamError(status.getReasonPhrase(), List.of());
        }

        String rawBody = new String(body, StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = readMessage(root, status.getReasonPhrase());
            List<ApiValidationError> validationErrors = readValidationErrors(root, message);
            return new ParsedUpstreamError(message, validationErrors);
        } catch (IOException ex) {
            return new ParsedUpstreamError(rawBody, List.of());
        }
    }

    private String readMessage(JsonNode root, String fallback) {
        if (root == null || root.isNull()) {
            return fallback;
        }
        if (root.hasNonNull("message")) {
            String message = root.get("message").asText();
            if (!message.isBlank() && !"Bad Request".equalsIgnoreCase(message)) {
                return message;
            }
        }
        if (root.hasNonNull("error")) {
            String error = root.get("error").asText();
            if (!error.isBlank()) {
                return error;
            }
        }
        if (root.hasNonNull("detail")) {
            String detail = root.get("detail").asText();
            if (!detail.isBlank()) {
                return detail;
            }
        }
        return fallback;
    }

    private List<ApiValidationError> readValidationErrors(JsonNode root, String message) {
        if (root != null && root.has("validationErrors") && root.get("validationErrors").isArray()) {
            ArrayNode validationErrorsNode = (ArrayNode) root.get("validationErrors");
            List<ApiValidationError> validationErrors = new ArrayList<>();
            validationErrorsNode.forEach(item -> validationErrors.add(new ApiValidationError(
                    item.hasNonNull("field") ? item.get("field").asText() : null,
                    item.hasNonNull("message") ? item.get("message").asText() : "Validation failed"
            )));
            return validationErrors;
        }

        if (message == null || message.isBlank() || !message.contains(":")) {
            return List.of();
        }

        List<ApiValidationError> parsed = new ArrayList<>();
        Arrays.stream(message.split(";"))
                .map(String::trim)
                .filter(part -> part.contains(":"))
                .forEach(part -> {
                    int separator = part.indexOf(':');
                    String field = part.substring(0, separator).trim();
                    String fieldMessage = part.substring(separator + 1).trim();
                    if (!field.isBlank() && !fieldMessage.isBlank()) {
                        parsed.add(new ApiValidationError(field, fieldMessage));
                    }
                });

        return parsed;
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        return StreamUtils.copyToByteArray(request.getInputStream());
    }

    private void copyRequestHeaders(
            HttpServletRequest request,
            HttpHeaders headers,
            Map<String, String> headerOverrides,
            HttpMethod method,
            String upstreamUri
    ) {
        Collections.list(request.getHeaderNames()).stream()
                .filter(name -> !shouldSkipHeader(name, SKIP_REQUEST_HEADERS))
                .forEach(name -> headers.addAll(name, Collections.list(request.getHeaders(name))));

        String accessTokenFromCookie = readCookie(request, authCookieProperties.getAccessName());
        String authorizationHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        boolean overrideAuthorizationProvided = headerOverrides.containsKey(HttpHeaders.AUTHORIZATION);

        if (!overrideAuthorizationProvided && accessTokenFromCookie != null && !accessTokenFromCookie.isBlank()) {
            if (authorizationHeader == null || !isLikelyBearerJwt(authorizationHeader)) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenFromCookie);
            }
        }

        if (isLearningRunSubmit(method, upstreamUri) && headers.getContentType() == null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        headerOverrides.forEach(headers::set);
    }

    private boolean hasAuthorizationHeader(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && !auth.isBlank()) {
            return true;
        }
        return readCookie(request, authCookieProperties.getAccessName()) != null;
    }

    private static boolean isLikelyBearerJwt(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        if (token.isBlank()) {
            return false;
        }
        return token.chars().filter(ch -> ch == '.').count() == 2;
    }

    private String readCookie(HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseEntity<byte[]> withoutUnsafeResponseHeaders(ResponseEntity<byte[]> upstream) {
        HttpHeaders headers = new HttpHeaders();
        upstream.getHeaders().forEach((name, values) -> {
            if (!shouldSkipHeader(name, SKIP_RESPONSE_HEADERS)) {
                headers.addAll(name, values);
            }
        });

        return ResponseEntity.status(upstream.getStatusCode())
                .headers(headers)
                .body(upstream.getBody());
    }

    private static boolean methodAllowsBody(String method) {
        return switch (method) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> false;
            default -> true;
        };
    }

    private static boolean shouldSkipHeader(String headerName, Set<String> skippedHeaders) {
        return skippedHeaders.stream().anyMatch(skipped -> skipped.equalsIgnoreCase(headerName));
    }

    private ResponseEntity<byte[]> errorResponse(
            HttpStatus status,
            String message,
            String requestId,
            List<ApiValidationError> validationErrors
    ) {
        ApiErrorResponse error = new ApiErrorResponse(
                status.value(),
                ErrorCodeMapper.codeFor(status.value()),
                message,
                requestId,
                validationErrors == null || validationErrors.isEmpty() ? null : validationErrors
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(error));
    }

    private String resolveRequestId(String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        return UUID.randomUUID().toString();
    }

    private byte[] toJson(ApiErrorResponse error) {
        try {
            return objectMapper.writeValueAsBytes(error);
        } catch (JsonProcessingException ex) {
            return ("{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"Error serialization failed\",\"requestId\":\""
                    + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    private record ParsedUpstreamError(String message, List<ApiValidationError> validationErrors) {
    }
}
