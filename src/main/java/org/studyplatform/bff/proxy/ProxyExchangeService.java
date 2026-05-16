package org.studyplatform.bff.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.exception.ApiErrorResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

@Service
public class ProxyExchangeService {

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

    public ProxyExchangeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<byte[]> exchange(HttpServletRequest request, WebClient client, String upstreamUri) {
        byte[] requestBody;
        try {
            requestBody = readBody(request);
        } catch (IOException ex) {
            return errorResponse(
                    HttpStatus.BAD_REQUEST,
                    "REQUEST_BODY_READ_FAILED",
                    "Cannot read request body",
                    request.getRequestURI()
            );
        }

        WebClient.RequestHeadersSpec<?> spec = client
                .method(HttpMethod.valueOf(request.getMethod()))
                .uri(upstreamUri)
                .headers(headers -> copyRequestHeaders(request, headers));

        if (requestBody.length > 0 && methodAllowsBody(request.getMethod())) {
            spec = ((WebClient.RequestBodySpec) spec).bodyValue(requestBody);
        }

        return spec.exchangeToMono(response -> response.toEntity(byte[].class))
                .map(this::withoutUnsafeResponseHeaders)
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(ex -> Mono.just(errorResponse(
                        HttpStatus.BAD_GATEWAY,
                        "UPSTREAM_SERVICE_ERROR",
                        "Upstream service is unavailable or returned an invalid response",
                        request.getRequestURI()
                )))
                .block();
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

    private byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() == 0) {
            return new byte[0];
        }
        return StreamUtils.copyToByteArray(request.getInputStream());
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpHeaders headers) {
        Collections.list(request.getHeaderNames()).stream()
                .filter(name -> !shouldSkipHeader(name, SKIP_REQUEST_HEADERS))
                .forEach(name -> headers.addAll(name, Collections.list(request.getHeaders(name))));
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
            String code,
            String message,
            String path
    ) {
        ApiErrorResponse error = new ApiErrorResponse(
                Instant.now().toString(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                path
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(error));
    }

    private byte[] toJson(ApiErrorResponse error) {
        try {
            return objectMapper.writeValueAsBytes(error);
        } catch (JsonProcessingException ex) {
            return "{\"status\":500,\"error\":\"Internal Server Error\",\"code\":\"ERROR_SERIALIZATION_FAILED\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
