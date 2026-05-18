package org.studyplatform.bff.learning.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.config.AuthCookieProperties;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/learn")
public class LearningMyCoursesController {

    private final WebClient learningWebClient;
    private final WebClient courseWebClient;
    private final ObjectMapper objectMapper;
    private final AuthCookieProperties authCookieProperties;

    public LearningMyCoursesController(
            @Qualifier("learningWebClient")
            WebClient learningWebClient,
            @Qualifier("courseWebClient")
            WebClient courseWebClient,
            ObjectMapper objectMapper,
            AuthCookieProperties authCookieProperties
    ) {
        this.learningWebClient = learningWebClient;
        this.courseWebClient = courseWebClient;
        this.objectMapper = objectMapper;
        this.authCookieProperties = authCookieProperties;
    }

    @GetMapping("/my-courses")
    public ResponseEntity<byte[]> myCourses(HttpServletRequest request) {
        String authorization = resolveAuthorization(request);
        if (authorization == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"Unauthorized\"}".getBytes());
        }

        ResponseEntity<byte[]> learningResponse = learningWebClient.get()
                .uri("/api/v1/learn/my-courses")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchangeToMono(res -> res.toEntity(byte[].class))
                .block();

        if (learningResponse == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"LearningService unavailable\"}".getBytes());
        }

        if (!learningResponse.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(learningResponse.getStatusCode())
                    .headers(learningResponse.getHeaders())
                    .body(learningResponse.getBody());
        }

        ArrayNode learningItems;
        try {
            JsonNode root = objectMapper.readTree(learningResponse.getBody());
            if (!(root instanceof ArrayNode arr)) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Invalid LearningService response\"}".getBytes());
            }
            learningItems = arr;
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"Invalid LearningService response\"}".getBytes());
        }

        List<ObjectNode> mergedItems = new ArrayList<>();
        for (JsonNode learningItem : learningItems) {
            long courseId = learningItem.path("courseId").asLong(-1);
            if (courseId <= 0) {
                continue;
            }

            ResponseEntity<byte[]> courseResponse = courseWebClient.get()
                    .uri("/api/v1/courses/{courseId}", courseId)
                    .exchangeToMono(res -> res.toEntity(byte[].class))
                    .block();

            if (courseResponse == null || !courseResponse.getStatusCode().is2xxSuccessful()) {
                continue;
            }

            try {
                JsonNode courseRaw = objectMapper.readTree(courseResponse.getBody());
                ObjectNode course = objectMapper.createObjectNode();
                copyIfPresent(courseRaw, course, "id");
                copyIfPresent(courseRaw, course, "slug");
                copyIfPresent(courseRaw, course, "title");
                copyIfPresent(courseRaw, course, "shortDescription");
                copyIfPresent(courseRaw, course, "difficulty");
                copyIfPresent(courseRaw, course, "accessType");
                copyIfPresent(courseRaw, course, "enrollmentEnabled");
                copyIfPresent(courseRaw, course, "coverImageUrl");
                copyIfPresent(courseRaw, course, "estimatedMinutes");

                ObjectNode merged = objectMapper.createObjectNode();
                merged.set("course", course);
                copyIfPresent(learningItem, merged, "progressPercent");
                copyIfPresent(learningItem, merged, "status");
                copyIfPresent(learningItem, merged, "nextItemId");
                mergedItems.add(merged);
            } catch (Exception ignored) {
                // Skip broken item and continue with the rest.
            }
        }

        byte[] responseBody = toJson(mergedItems);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseBody);
    }

    private String resolveAuthorization(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            return authorization;
        }
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (authCookieProperties.getAccessName().equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return "Bearer " + cookie.getValue();
            }
        }
        return null;
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String fieldName) {
        if (source.has(fieldName)) {
            target.set(fieldName, source.get(fieldName));
        }
    }

    private byte[] toJson(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            return "[]".getBytes();
        }
    }
}
