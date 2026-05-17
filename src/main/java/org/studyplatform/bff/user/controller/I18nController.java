package org.studyplatform.bff.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/i18n")
public class I18nController {

    private static final List<String> SUPPORTED_LOCALES = List.of("ru", "en");
    private static final String FALLBACK_LOCALE = "ru";

    private final WebClient userWebClient;
    private final ProxyExchangeService proxyExchangeService;
    private final ObjectMapper objectMapper;

    public I18nController(
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ProxyExchangeService proxyExchangeService,
            ObjectMapper objectMapper
    ) {
        this.userWebClient = userWebClient;
        this.proxyExchangeService = proxyExchangeService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/default-locale")
    public ResponseEntity<byte[]> defaultLocale(HttpServletRequest request) {
        ResponseEntity<byte[]> meResponse = proxyExchangeService.exchange(
                request,
                userWebClient,
                HttpMethod.GET,
                "/api/v1/users/me",
                null,
                java.util.Map.of()
        );

        String accountLocale = extractPreferredLocale(meResponse);
        if (accountLocale != null) {
            return localeResponse(accountLocale, "ACCOUNT_SETTING");
        }

        String acceptLanguageLocale = resolveFromAcceptLanguage(request.getHeader("Accept-Language"));
        if (acceptLanguageLocale != null) {
            return localeResponse(acceptLanguageLocale, "ACCEPT_LANGUAGE");
        }

        return localeResponse(FALLBACK_LOCALE, "FALLBACK");
    }

    private ResponseEntity<byte[]> localeResponse(String locale, String source) {
        String body = "{\"locale\":\"" + locale + "\",\"source\":\"" + source + "\"}";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private String extractPreferredLocale(ResponseEntity<byte[]> response) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().length == 0) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.hasNonNull("preferredLocale")) {
                return null;
            }
            String locale = root.get("preferredLocale").asText().toLowerCase(Locale.ROOT);
            return SUPPORTED_LOCALES.contains(locale) ? locale : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private String resolveFromAcceptLanguage(String acceptLanguageHeader) {
        if (acceptLanguageHeader == null || acceptLanguageHeader.isBlank()) {
            return null;
        }

        for (String rawPart : acceptLanguageHeader.split(",")) {
            String value = rawPart.trim();
            if (value.isBlank()) {
                continue;
            }
            String language = value.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (language.startsWith("ru")) {
                return "ru";
            }
            if (language.startsWith("en")) {
                return "en";
            }
        }
        return null;
    }
}
