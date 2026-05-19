package org.studyplatform.bff.course.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.StreamUtils;
import org.studyplatform.bff.config.AuthCookieProperties;
import org.studyplatform.bff.proxy.ProxyExchangeService;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teacher")
public class CourseTeacherProxyController {

    private static final String TEACHER_PREFIX = "/api/v1/teacher";
    private static final String TEACHER_ITEMS_PREFIX = "/api/v1/teacher/items";
    private static final String ADMIN_PREFIX = "/api/v1/admin";
    private static final String ADMIN_COURSE_ITEMS_PREFIX = "/api/v1/admin/course-items";
    private static final String TEACHER_CREATE_COURSE_PATH = "/api/v1/teacher/courses";
    private static final String TEACHER_ITEM_CONTENT_BLOCKS_SUFFIX = "/content-blocks";
    private static final String TEACHER_ITEM_TEST_CASES_SUFFIX = "/test-cases";

    private final WebClient courseWebClient;
    private final ProxyExchangeService proxyExchangeService;
    private final ObjectMapper objectMapper;
    private final AuthCookieProperties authCookieProperties;

    public CourseTeacherProxyController(
            @Qualifier("courseWebClient")
            WebClient courseWebClient,
            ProxyExchangeService proxyExchangeService,
            ObjectMapper objectMapper,
            AuthCookieProperties authCookieProperties
    ) {
        this.courseWebClient = courseWebClient;
        this.proxyExchangeService = proxyExchangeService;
        this.objectMapper = objectMapper;
        this.authCookieProperties = authCookieProperties;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxyTeacherRequest(HttpServletRequest request) {
        String upstreamUri = buildTeacherUpstreamUri(request);
        if (isTeacherCreateCourse(request)) {
            byte[] patchedBody = injectCreatedByUserIdIfNeeded(request);
            return proxyExchangeService.exchange(request, courseWebClient, upstreamUri, patchedBody, Map.of());
        }
        if (isTeacherReplaceTestCases(request)) {
            byte[] wrappedBody = wrapTeacherTestCasesArrayIfNeeded(request);
            return proxyExchangeService.exchange(request, courseWebClient, upstreamUri, wrappedBody, Map.of());
        }
        if (isTeacherReplaceContentBlocks(request)) {
            byte[] wrappedBody = wrapTeacherContentBlocksArrayIfNeeded(request);
            return proxyExchangeService.exchange(request, courseWebClient, upstreamUri, wrappedBody, Map.of());
        }

        return proxyExchangeService.exchange(
                request,
                courseWebClient,
                upstreamUri
        );
    }

    private String buildTeacherUpstreamUri(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String upstreamPath;

        if (requestUri.startsWith(TEACHER_ITEMS_PREFIX + "/")) {
            upstreamPath = ADMIN_COURSE_ITEMS_PREFIX + requestUri.substring(TEACHER_ITEMS_PREFIX.length());
        } else {
            upstreamPath = requestUri.replaceFirst("^" + TEACHER_PREFIX, ADMIN_PREFIX);
        }

        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        return upstreamPath + query;
    }

    private boolean isTeacherCreateCourse(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && TEACHER_CREATE_COURSE_PATH.equals(request.getRequestURI());
    }

    private boolean isTeacherReplaceTestCases(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return HttpMethod.PUT.matches(request.getMethod())
                && uri.startsWith(TEACHER_ITEMS_PREFIX + "/")
                && uri.endsWith(TEACHER_ITEM_TEST_CASES_SUFFIX);
    }

    private boolean isTeacherReplaceContentBlocks(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return HttpMethod.PUT.matches(request.getMethod())
                && uri.startsWith(TEACHER_ITEMS_PREFIX + "/")
                && uri.endsWith(TEACHER_ITEM_CONTENT_BLOCKS_SUFFIX);
    }

    private byte[] wrapTeacherContentBlocksArrayIfNeeded(HttpServletRequest request) {
        return wrapArrayBodyWithFieldName(request, "contentBlocks");
    }

    private byte[] wrapTeacherTestCasesArrayIfNeeded(HttpServletRequest request) {
        return wrapArrayBodyWithFieldName(request, "testCases");
    }

    private byte[] wrapArrayBodyWithFieldName(HttpServletRequest request, String fieldName) {
        byte[] rawBody = readBodySafe(request);
        if (rawBody.length == 0) {
            return rawBody;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (!root.isArray()) {
                return rawBody;
            }
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.set(fieldName, root);
            return objectMapper.writeValueAsBytes(wrapped);
        } catch (IOException ex) {
            return rawBody;
        }
    }

    private byte[] injectCreatedByUserIdIfNeeded(HttpServletRequest request) {
        byte[] rawBody = readBodySafe(request);
        if (rawBody.length == 0) {
            return rawBody;
        }

        Long userId = extractUserIdFromToken(request);
        if (userId == null) {
            return rawBody;
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (!(root instanceof ObjectNode objectNode)) {
                return rawBody;
            }

            if (objectNode.hasNonNull("createdByUserId")) {
                return rawBody;
            }

            objectNode.put("createdByUserId", userId);
            return objectMapper.writeValueAsBytes(objectNode);
        } catch (IOException ex) {
            return rawBody;
        }
    }

    private Long extractUserIdFromToken(HttpServletRequest request) {
        String token = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            token = readCookieToken(request);
        }
        if (token == null) {
            return null;
        }
        return extractSubAsLong(token);
    }

    private String readCookieToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        String cookieName = authCookieProperties.getAccessName();
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        return token.isBlank() ? null : token;
    }

    private Long extractSubAsLong(String jwtToken) {
        String[] parts = jwtToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);
            if (!payload.hasNonNull("sub")) {
                return null;
            }
            return Long.parseLong(payload.get("sub").asText());
        } catch (Exception ex) {
            return null;
        }
    }

    private byte[] readBodySafe(HttpServletRequest request) {
        try {
            return StreamUtils.copyToByteArray(request.getInputStream());
        } catch (IOException ex) {
            return new byte[0];
        }
    }
}
