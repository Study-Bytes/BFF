package org.studyplatform.bff.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.studyplatform.bff.config.AuthCookieProperties;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthCookieService {
    private final ObjectMapper objectMapper;
    private final AuthCookieProperties properties;

    public AuthCookieService(ObjectMapper objectMapper, AuthCookieProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public byte[] withRefreshTokenFromCookieIfMissing(byte[] rawRequestBody, HttpServletRequest request) {
        String refreshFromCookie = readCookie(request, properties.getRefreshName());
        if (refreshFromCookie == null || refreshFromCookie.isBlank()) {
            return rawRequestBody;
        }

        try {
            JsonNode parsed = rawRequestBody == null || rawRequestBody.length == 0
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawRequestBody);
            com.fasterxml.jackson.databind.node.ObjectNode root = parsed != null && parsed.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) parsed
                    : objectMapper.createObjectNode();
            if (root.hasNonNull("refreshToken") && !root.get("refreshToken").asText().isBlank()) {
                return rawRequestBody;
            }

            root.put("refreshToken", refreshFromCookie);
            return objectMapper.writeValueAsBytes(root);
        } catch (IOException ex) {
            return rawRequestBody;
        }
    }

    public void applyAuthCookiesFromResponse(byte[] responseBody, HttpHeaders responseHeaders) {
        Optional<String> accessToken = readField(responseBody, "accessToken");
        Optional<String> refreshToken = readField(responseBody, "refreshToken");

        accessToken.ifPresent(token -> responseHeaders.add(HttpHeaders.SET_COOKIE, buildAccessCookie(token).toString()));
        refreshToken.ifPresent(token -> responseHeaders.add(HttpHeaders.SET_COOKIE, buildRefreshCookie(token).toString()));
    }

    public void clearAuthCookies(HttpHeaders responseHeaders) {
        responseHeaders.add(HttpHeaders.SET_COOKIE, clearCookie(properties.getAccessName()).toString());
        responseHeaders.add(HttpHeaders.SET_COOKIE, clearCookie(properties.getRefreshName()).toString());
    }

    public Map<String, String> authorizationFromAccessCookie(HttpServletRequest request) {
        String accessToken = readCookie(request, properties.getAccessName());
        if (accessToken == null || accessToken.isBlank()) {
            return Map.of();
        }
        return Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    public String readCookie(HttpServletRequest request, String cookieName) {
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

    private Optional<String> readField(byte[] responseBody, String fieldName) {
        if (responseBody == null || responseBody.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.hasNonNull(fieldName)) {
                return Optional.empty();
            }
            String value = root.get(fieldName).asText();
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private ResponseCookie buildAccessCookie(String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getAccessName(), value)
                .httpOnly(true)
                .secure(properties.isSecure())
                .path(properties.getPath())
                .sameSite(properties.getSameSite());

        if (properties.getDomain() != null && !properties.getDomain().isBlank()) {
            builder.domain(properties.getDomain());
        }
        if (properties.getAccessMaxAgeSeconds() != null) {
            builder.maxAge(Duration.ofSeconds(properties.getAccessMaxAgeSeconds()));
        }
        return builder.build();
    }

    private ResponseCookie buildRefreshCookie(String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getRefreshName(), value)
                .httpOnly(true)
                .secure(properties.isSecure())
                .path(properties.getPath())
                .sameSite(properties.getSameSite());

        if (properties.getDomain() != null && !properties.getDomain().isBlank()) {
            builder.domain(properties.getDomain());
        }
        if (properties.getRefreshMaxAgeSeconds() != null) {
            builder.maxAge(Duration.ofSeconds(properties.getRefreshMaxAgeSeconds()));
        }
        return builder.build();
    }

    private ResponseCookie clearCookie(String name) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(properties.isSecure())
                .path(properties.getPath())
                .sameSite(properties.getSameSite())
                .maxAge(Duration.ZERO);

        if (properties.getDomain() != null && !properties.getDomain().isBlank()) {
            builder.domain(properties.getDomain());
        }
        return builder.build();
    }
}
