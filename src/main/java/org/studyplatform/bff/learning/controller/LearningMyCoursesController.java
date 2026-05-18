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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.config.AuthCookieProperties;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @GetMapping("/courses/{courseId}")
    public ResponseEntity<byte[]> learnCourse(
            @PathVariable Long courseId,
            HttpServletRequest request
    ) {
        String authorization = resolveAuthorization(request);
        if (authorization == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"Unauthorized\"}".getBytes());
        }

        ResponseEntity<byte[]> learningResponse = learningWebClient.get()
                .uri("/api/v1/learn/courses/{courseId}", courseId)
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

        ResponseEntity<byte[]> courseResponse = courseWebClient.get()
                .uri("/api/v1/courses/{courseId}", courseId)
                .exchangeToMono(res -> res.toEntity(byte[].class))
                .block();
        if (courseResponse == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"CourseService unavailable\"}".getBytes());
        }
        if (!courseResponse.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(courseResponse.getStatusCode())
                    .headers(courseResponse.getHeaders())
                    .body(courseResponse.getBody());
        }

        try {
            JsonNode learning = objectMapper.readTree(learningResponse.getBody());
            JsonNode course = objectMapper.readTree(courseResponse.getBody());
            ObjectNode merged = mergeLearningCourse(course, learning);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(merged));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"Invalid upstream response\"}".getBytes());
        }
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

    private ObjectNode mergeLearningCourse(JsonNode course, JsonNode learning) {
        ObjectNode merged = objectMapper.createObjectNode();
        copyIfPresent(course, merged, "id");
        copyIfPresent(course, merged, "slug");
        copyIfPresent(course, merged, "title");
        copyIfPresent(course, merged, "shortDescription");
        copyIfPresent(course, merged, "difficulty");
        copyIfPresent(course, merged, "accessType");
        copyIfPresent(course, merged, "enrollmentEnabled");
        copyIfPresent(course, merged, "coverImageUrl");
        copyIfPresent(course, merged, "estimatedMinutes");
        copyIfPresent(course, merged, "description");
        copyIfPresent(course, merged, "status");

        copyIfPresent(learning, merged, "progressPercent");
        copyIfPresent(learning, merged, "enrollmentStatus");
        copyIfPresent(learning, merged, "nextItemId");

        Map<Long, JsonNode> learningItemsById = new HashMap<>();
        JsonNode learningItems = learning.path("items");
        if (learningItems.isArray()) {
            for (JsonNode item : learningItems) {
                long itemId = item.path("itemId").asLong(-1);
                if (itemId > 0) {
                    learningItemsById.put(itemId, item);
                }
            }
        }

        ArrayNode mergedModules = objectMapper.createArrayNode();
        JsonNode modules = course.path("modules");
        if (modules.isArray()) {
            for (JsonNode module : modules) {
                ObjectNode mergedModule = objectMapper.createObjectNode();
                copyIfPresent(module, mergedModule, "id");
                copyIfPresent(module, mergedModule, "title");
                copyIfPresent(module, mergedModule, "orderIndex");

                ArrayNode mergedItems = objectMapper.createArrayNode();
                JsonNode moduleItems = module.path("items");
                if (moduleItems.isArray()) {
                    for (JsonNode courseItem : moduleItems) {
                        ObjectNode mergedItem = objectMapper.createObjectNode();
                        copyIfPresent(courseItem, mergedItem, "id");
                        copyIfPresent(courseItem, mergedItem, "title");
                        copyIfPresent(courseItem, mergedItem, "itemType");
                        copyIfPresent(courseItem, mergedItem, "orderIndex");
                        copyIfPresent(courseItem, mergedItem, "estimatedMinutes");

                        long itemId = courseItem.path("id").asLong(-1);
                        JsonNode learningItem = learningItemsById.get(itemId);
                        boolean completed = learningItem != null && learningItem.path("completed").asBoolean(false);
                        boolean locked = learningItem != null && learningItem.path("locked").asBoolean(false);
                        mergedItem.put("completed", completed);
                        mergedItem.put("locked", locked);
                        mergedItems.add(mergedItem);
                    }
                }
                mergedModule.set("items", mergedItems);
                mergedModules.add(mergedModule);
            }
        }
        merged.set("modules", mergedModules);
        return merged;
    }
}
