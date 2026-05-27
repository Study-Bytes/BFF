package org.studyplatform.bff.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.studyplatform.bff.proxy.ProxyExchangeService;
import org.studyplatform.bff.user.service.AuthCookieService;
import org.studyplatform.bff.user.service.AvatarStorageService;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class UserProxyController {

    private final WebClient userWebClient;
    private final ProxyExchangeService proxyExchangeService;
    private final AuthCookieService authCookieService;
    private final AvatarStorageService avatarStorageService;
    private final ObjectMapper objectMapper;

    public UserProxyController(
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ProxyExchangeService proxyExchangeService,
            AuthCookieService authCookieService,
            AvatarStorageService avatarStorageService,
            ObjectMapper objectMapper
    ) {
        this.userWebClient = userWebClient;
        this.proxyExchangeService = proxyExchangeService;
        this.authCookieService = authCookieService;
        this.avatarStorageService = avatarStorageService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/me")
    public ResponseEntity<byte[]> currentUser(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildCurrentUserUri(request)
        );
    }

    @PutMapping({"/me/profile", "/me/password"})
    public ResponseEntity<byte[]> updateCurrentUser(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildCurrentUserUri(request)
        );
    }

    @GetMapping("/me/settings")
    public ResponseEntity<byte[]> currentUserSettings(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildCurrentUserUri(request)
        );
    }

    @PutMapping("/me/settings")
    public ResponseEntity<byte[]> updateCurrentUserSettings(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildCurrentUserUri(request)
        );
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> uploadAvatar(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file
    ) {
        Map<String, String> authHeaderOverrides = authCookieService.authorizationFromAccessCookie(request);
        ResponseEntity<byte[]> currentUserResponse = proxyExchangeService.exchange(
                request,
                userWebClient,
                HttpMethod.GET,
                "/api/v1/users/me",
                null,
                authHeaderOverrides
        );

        if (currentUserResponse.getStatusCode().isError()) {
            return currentUserResponse;
        }

        JsonNode currentUser = readCurrentUser(currentUserResponse);
        Long userId = currentUser.path("id").isNumber() ? currentUser.path("id").asLong() : null;
        if (userId == null) {
            return ResponseEntity.status(HttpStatusCode.valueOf(502))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":502,\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"UserService returned invalid current user\"}".getBytes());
        }

        AvatarStorageService.StoredAvatar storedAvatar = avatarStorageService.store(userId, file);
        String previousAvatarUrl = textOrNull(currentUser, "avatarUrl");
        String publicAvatarUrl = publicAvatarUrl(storedAvatar.publicPath());

        byte[] settingsBody = buildSettingsBody(currentUser, publicAvatarUrl);
        ResponseEntity<byte[]> updateResponse = proxyExchangeService.exchange(
                request,
                userWebClient,
                HttpMethod.PUT,
                "/api/v1/users/me/settings",
                settingsBody,
                withJsonContentType(authHeaderOverrides)
        );

        if (updateResponse.getStatusCode().isError()) {
            avatarStorageService.deleteStoredAvatar(storedAvatar.fileName());
            return updateResponse;
        }

        avatarStorageService.deleteStoredAvatarByUrl(previousAvatarUrl);
        return updateResponse;
    }

    private Map<String, String> withJsonContentType(Map<String, String> authHeaderOverrides) {
        if (authHeaderOverrides.isEmpty()) {
            return Map.of(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        }
        return Map.of(
                HttpHeaders.AUTHORIZATION, authHeaderOverrides.get(HttpHeaders.AUTHORIZATION),
                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE
        );
    }

    private JsonNode readCurrentUser(ResponseEntity<byte[]> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (IOException ex) {
            throw new IllegalStateException("UserService returned invalid current user", ex);
        }
    }

    private byte[] buildSettingsBody(JsonNode currentUser, String avatarUrl) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fullName", textOrFallback(currentUser, "fullName", textOrFallback(currentUser, "email", "User")));
        payload.put("avatarUrl", avatarUrl);
        putNullableText(payload, "bio", currentUser);
        payload.put("preferredLocale", textOrFallback(currentUser, "preferredLocale", "ru"));
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not serialize avatar settings update", ex);
        }
    }

    private String publicAvatarUrl(String publicPath) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(publicPath)
                .toUriString();
    }

    private void putNullableText(ObjectNode payload, String field, JsonNode source) {
        String value = textOrNull(source, field);
        if (value == null) {
            payload.putNull(field);
        } else {
            payload.put(field, value);
        }
    }

    private String textOrFallback(JsonNode source, String field, String fallback) {
        String value = textOrNull(source, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String textOrNull(JsonNode source, String field) {
        JsonNode value = source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
