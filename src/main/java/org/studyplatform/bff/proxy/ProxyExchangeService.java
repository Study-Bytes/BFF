package org.studyplatform.bff.proxy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    public ResponseEntity<byte[]> exchange(HttpServletRequest request, WebClient client, String upstreamUri) {
        byte[] requestBody;
        try {
            requestBody = readBody(request);
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(("Cannot read body: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8));
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
                .onErrorResume(ex -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(("Upstream error: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8))))
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

    private byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() == 0) {
            return new byte[0];
        }
        return StreamUtils.copyToByteArray(request.getInputStream());
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpHeaders headers) {
        Collections.list(request.getHeaderNames()).stream()
                .filter(name -> !SKIP_REQUEST_HEADERS.contains(name))
                .forEach(name -> headers.addAll(name, Collections.list(request.getHeaders(name))));
    }

    private ResponseEntity<byte[]> withoutUnsafeResponseHeaders(ResponseEntity<byte[]> upstream) {
        HttpHeaders headers = new HttpHeaders();
        upstream.getHeaders().forEach((name, values) -> {
            if (!SKIP_RESPONSE_HEADERS.contains(name)) {
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
}
