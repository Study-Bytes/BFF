package org.studyplatform.bff.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;
import org.studyplatform.bff.user.service.AuthCookieService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthProxyController {

    private final WebClient userWebClient;
    private final ProxyExchangeService proxyExchangeService;
    private final AuthCookieService authCookieService;
    private final ObjectMapper objectMapper;

    public AuthProxyController(
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ProxyExchangeService proxyExchangeService,
            AuthCookieService authCookieService,
            ObjectMapper objectMapper
    ) {
        this.userWebClient = userWebClient;
        this.proxyExchangeService = proxyExchangeService;
        this.authCookieService = authCookieService;
        this.objectMapper = objectMapper;
    }

    @PostMapping({"/register", "/login"})
    public ResponseEntity<byte[]> registerOrLogin(HttpServletRequest request) {
        ResponseEntity<byte[]> upstream = proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
        return withAuthCookies(upstream);
    }

    @PostMapping("/refresh")
    public ResponseEntity<byte[]> refresh(HttpServletRequest request) {
        byte[] rawBody = readBodySafe(request);
        byte[] requestBody = authCookieService.withRefreshTokenFromCookieIfMissing(rawBody, request);

        ResponseEntity<byte[]> upstream = proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request),
                requestBody,
                Map.of()
        );
        return withAuthCookies(upstream);
    }

    @PostMapping("/logout")
    public ResponseEntity<byte[]> logout(HttpServletRequest request) {
        ResponseEntity<byte[]> upstream = proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request),
                null,
                authCookieService.authorizationFromAccessCookie(request)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.putAll(upstream.getHeaders());
        authCookieService.clearAuthCookies(headers);

        if (upstream.getStatusCode().is2xxSuccessful()) {
            byte[] body = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity.ok().headers(headers).body(body);
        }

        return ResponseEntity.status(upstream.getStatusCode())
                .headers(headers)
                .body(upstream.getBody());
    }

    @PostMapping("/register-teacher-request")
    public ResponseEntity<byte[]> registerTeacherRequest(HttpServletRequest request) {
        byte[] rawBody = readBodySafe(request);
        if (rawBody.length == 0) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"status\":400,\"code\":\"VALIDATION_ERROR\",\"message\":\"Request body is required\",\"requestId\":\""
                            + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"status\":400,\"code\":\"VALIDATION_ERROR\",\"message\":\"Invalid JSON body\",\"requestId\":\""
                            + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
        }

        byte[] registerPayload = extractRegisterPayload(root);
        ResponseEntity<byte[]> registerResponse = proxyExchangeService.exchange(
                request,
                userWebClient,
                "/api/v1/auth/register",
                registerPayload,
                Map.of()
        );
        if (!registerResponse.getStatusCode().is2xxSuccessful()) {
            return registerResponse;
        }

        JsonNode registerRoot;
        try {
            registerRoot = objectMapper.readTree(registerResponse.getBody());
        } catch (IOException ex) {
            return registerResponse;
        }
        String accessToken = text(registerRoot, "accessToken");
        String fullName = text(root, "fullName");
        String preferredLocale = text(root, "preferredLocale");
        JsonNode userNodeForResponse = registerRoot.get("user");

        if (accessToken != null && fullName != null && preferredLocale != null) {
            byte[] settingsPayload = buildSettingsPayload(root, fullName, preferredLocale);
            ResponseEntity<byte[]> settingsResponse = proxyExchangeService.exchange(
                    request,
                    userWebClient,
                    HttpMethod.PUT,
                    "/api/v1/users/me/settings",
                    settingsPayload,
                    Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            );
            if (!settingsResponse.getStatusCode().is2xxSuccessful()) {
                return settingsResponse;
            }
            try {
                userNodeForResponse = objectMapper.readTree(settingsResponse.getBody());
            } catch (IOException ignored) {
            }
        }

        byte[] teacherPayload = extractTeacherRequestPayload(root);
        ResponseEntity<byte[]> teacherResponse = proxyExchangeService.exchange(
                request,
                userWebClient,
                "/api/v1/teacher-requests",
                teacherPayload,
                accessToken == null ? Map.of() : Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        );

        if (!teacherResponse.getStatusCode().is2xxSuccessful()) {
            return teacherResponse;
        }

        byte[] responseBody;
        try {
            JsonNode teacherRoot = objectMapper.readTree(teacherResponse.getBody());
            JsonNode result = objectMapper.createObjectNode()
                    .set("user", userNodeForResponse);
            ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("teacherRequest",
                    objectMapper.createObjectNode()
                            .put("id", teacherRoot.path("id").asLong())
                            .put("status", teacherRoot.path("status").asText())
            );
            copyAuthField(registerRoot, (com.fasterxml.jackson.databind.node.ObjectNode) result, "accessToken");
            copyAuthField(registerRoot, (com.fasterxml.jackson.databind.node.ObjectNode) result, "refreshToken");
            copyAuthField(registerRoot, (com.fasterxml.jackson.databind.node.ObjectNode) result, "tokenType");
            if (registerRoot.has("expiresIn")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("expiresIn", registerRoot.get("expiresIn"));
            }
            responseBody = objectMapper.writeValueAsBytes(result);
        } catch (IOException ex) {
            return teacherResponse;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.putAll(registerResponse.getHeaders());
        authCookieService.applyAuthCookiesFromResponse(registerResponse.getBody(), headers);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.ok().headers(headers).body(responseBody);
    }

    @GetMapping("/csrf")
    public ResponseEntity<byte[]> csrf() {
        String token = UUID.randomUUID().toString();
        byte[] body = ("{\"csrfToken\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<byte[]> jwks(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    private ResponseEntity<byte[]> withAuthCookies(ResponseEntity<byte[]> upstream) {
        if (!upstream.getStatusCode().is2xxSuccessful()) {
            return upstream;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(upstream.getHeaders());
        authCookieService.applyAuthCookiesFromResponse(upstream.getBody(), headers);
        return ResponseEntity.status(upstream.getStatusCode())
                .headers(headers)
                .body(upstream.getBody());
    }

    private byte[] extractRegisterPayload(JsonNode root) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        copyField(root, payload, "fullName");
        copyField(root, payload, "email");
        copyField(root, payload, "password");
        copyField(root, payload, "role");
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private byte[] extractTeacherRequestPayload(JsonNode root) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        copyField(root, payload, "motivation");
        copyField(root, payload, "experience");
        if (root.has("portfolioUrl")) {
            payload.set("portfolioUrl", root.get("portfolioUrl"));
        }
        if (root.has("preferredTopics")) {
            payload.set("preferredTopics", root.get("preferredTopics"));
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private byte[] buildSettingsPayload(JsonNode root, String fullName, String preferredLocale) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fullName", fullName);
        payload.put("preferredLocale", preferredLocale);
        if (root.has("avatarUrl")) {
            payload.set("avatarUrl", root.get("avatarUrl"));
        } else {
            payload.putNull("avatarUrl");
        }
        if (root.has("bio")) {
            payload.set("bio", root.get("bio"));
        } else {
            payload.putNull("bio");
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private void copyField(JsonNode source, com.fasterxml.jackson.databind.node.ObjectNode target, String fieldName) {
        if (source.has(fieldName)) {
            target.set(fieldName, source.get(fieldName));
        }
    }

    private void copyAuthField(JsonNode source, com.fasterxml.jackson.databind.node.ObjectNode target, String fieldName) {
        if (source.hasNonNull(fieldName)) {
            target.set(fieldName, source.get(fieldName));
        }
    }

    private String text(JsonNode root, String field) {
        if (root == null || !root.hasNonNull(field)) {
            return null;
        }
        String value = root.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private byte[] readBodySafe(HttpServletRequest request) {
        try {
            return StreamUtils.copyToByteArray(request.getInputStream());
        } catch (IOException ex) {
            return new byte[0];
        }
    }
}
