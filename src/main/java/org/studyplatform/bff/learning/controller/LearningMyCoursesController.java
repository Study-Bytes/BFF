package org.studyplatform.bff.learning.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.studyplatform.bff.config.AuthCookieProperties;

import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/learn")
public class LearningMyCoursesController {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final WebClient learningWebClient;
    private final WebClient courseWebClient;
    private final WebClient userWebClient;
    private final ObjectMapper objectMapper;
    private final AuthCookieProperties authCookieProperties;
    private final String courseInternalApiKey;

    public LearningMyCoursesController(
            @Qualifier("learningWebClient")
            WebClient learningWebClient,
            @Qualifier("courseWebClient")
            WebClient courseWebClient,
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ObjectMapper objectMapper,
            AuthCookieProperties authCookieProperties,
            @Value("${svc.course.internal-api-key:dev-course-service-internal-key}") String courseInternalApiKey
    ) {
        this.learningWebClient = learningWebClient;
        this.courseWebClient = courseWebClient;
        this.userWebClient = userWebClient;
        this.objectMapper = objectMapper;
        this.authCookieProperties = authCookieProperties;
        this.courseInternalApiKey = courseInternalApiKey;
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
        Set<Long> addedCourseIds = new HashSet<>();
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
                merged.put("relation", "LEARNER");
                mergedItems.add(merged);
                addedCourseIds.add(courseId);
            } catch (Exception ignored) {
                // Skip broken item and continue with the rest.
            }
        }

        addTeacherOwnedCourses(request, authorization, mergedItems, addedCourseIds);

        byte[] responseBody = toJson(mergedItems);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseBody);
    }

    @GetMapping("/courses/{courseId}/leaderboard")
    public ResponseEntity<byte[]> courseLeaderboard(
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
                .uri("/api/v1/learn/courses/{courseId}/leaderboard", courseId)
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

        try {
            JsonNode leaderboard = objectMapper.readTree(learningResponse.getBody());
            ObjectNode enriched = objectMapper.createObjectNode();
            copyIfPresent(leaderboard, enriched, "courseId");

            Map<Long, JsonNode> usersById = loadLeaderboardUsers(leaderboard, request, authorization);
            enriched.set("top", enrichLeaderboardEntries(leaderboard.path("top"), usersById, request));
            JsonNode currentUser = leaderboard.path("currentUser");
            if (currentUser.isMissingNode() || currentUser.isNull()) {
                enriched.putNull("currentUser");
            } else {
                enriched.set("currentUser", enrichLeaderboardEntry(currentUser, usersById, request, null));
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(enriched));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"Invalid upstream response\"}".getBytes());
        }
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

    @GetMapping("/courses/{courseId}/items/{itemId}")
    public ResponseEntity<byte[]> learnCourseItem(
            @PathVariable Long courseId,
            @PathVariable Long itemId,
            HttpServletRequest request
    ) {
        String authorization = resolveAuthorization(request);
        if (authorization == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"Unauthorized\"}".getBytes());
        }

        ResponseEntity<byte[]> learningItemResponse = learningWebClient.get()
                .uri("/api/v1/learn/courses/{courseId}/items/{itemId}", courseId, itemId)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchangeToMono(res -> res.toEntity(byte[].class))
                .block();
        if (learningItemResponse == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"LearningService unavailable\"}".getBytes());
        }
        if (!learningItemResponse.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(learningItemResponse.getStatusCode())
                    .headers(learningItemResponse.getHeaders())
                    .body(learningItemResponse.getBody());
        }

        ResponseEntity<byte[]> itemContentResponse = courseWebClient.get()
                .uri("/api/v1/internal/course-items/{itemId}/content", itemId)
                .header(INTERNAL_API_KEY_HEADER, courseInternalApiKey)
                .exchangeToMono(res -> res.toEntity(byte[].class))
                .block();
        if (itemContentResponse == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"CourseService unavailable\"}".getBytes());
        }
        if (!itemContentResponse.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(itemContentResponse.getStatusCode())
                    .headers(itemContentResponse.getHeaders())
                    .body(itemContentResponse.getBody());
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
            JsonNode learningItem = objectMapper.readTree(learningItemResponse.getBody());
            JsonNode itemContent = objectMapper.readTree(itemContentResponse.getBody());
            JsonNode course = objectMapper.readTree(courseResponse.getBody());
            ObjectNode merged = mergeLearningItem(course, itemContent, learningItem);
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

    private Map<Long, JsonNode> loadLeaderboardUsers(JsonNode leaderboard, HttpServletRequest request, String authorization) {
        Set<Long> userIds = new HashSet<>();
        collectUserIds(leaderboard.path("top"), userIds);
        JsonNode currentUser = leaderboard.path("currentUser");
        if (!currentUser.isMissingNode() && !currentUser.isNull()) {
            Long currentUserId = readUserId(currentUser);
            if (currentUserId != null) {
                userIds.add(currentUserId);
            }
        }

        Map<Long, JsonNode> usersById = new HashMap<>();
        Long jwtUserId = extractUserIdFromToken(request);
        for (Long userId : userIds) {
            JsonNode profile = loadUserProfile(userId, jwtUserId, authorization);
            if (profile != null) {
                usersById.put(userId, profile);
            }
        }
        return usersById;
    }

    private void collectUserIds(JsonNode entries, Set<Long> userIds) {
        if (!entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            Long userId = readUserId(entry);
            if (userId != null) {
                userIds.add(userId);
            }
        }
    }

    private JsonNode loadUserProfile(Long userId, Long jwtUserId, String authorization) {
        try {
            WebClient.RequestHeadersSpec<?> spec = userId.equals(jwtUserId)
                    ? userWebClient
                    .method(HttpMethod.GET)
                    .uri("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    : userWebClient
                    .method(HttpMethod.GET)
                    .uri("/api/v1/users/{id}", userId)
                    .header(HttpHeaders.AUTHORIZATION, authorization);
            ResponseEntity<byte[]> response = spec.exchangeToMono(res -> res.toEntity(byte[].class)).block();
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                return null;
            }
            return objectMapper.readTree(response.getBody());
        } catch (Exception ex) {
            return null;
        }
    }

    private ArrayNode enrichLeaderboardEntries(JsonNode entries, Map<Long, JsonNode> usersById, HttpServletRequest request) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!entries.isArray()) {
            return result;
        }
        int fallbackRank = 1;
        for (JsonNode entry : entries) {
            result.add(enrichLeaderboardEntry(entry, usersById, request, fallbackRank));
            fallbackRank++;
        }
        return result;
    }

    private ObjectNode enrichLeaderboardEntry(
            JsonNode entry,
            Map<Long, JsonNode> usersById,
            HttpServletRequest request,
            Integer fallbackRank
    ) {
        Long userId = readUserId(entry);
        JsonNode user = userId == null ? null : usersById.get(userId);

        ObjectNode result = objectMapper.createObjectNode();
        if (userId == null) {
            result.putNull("userId");
        } else {
            result.put("userId", userId);
        }
        putNullableText(result, "fullName", user, "fullName");
        result.set("avatarUrl", avatarUrlNode(user == null ? null : textOrNull(user, "avatarUrl"), request));
        putProgressPercent(result, entry);
        Integer rank = readInt(entry, "rank", "place", "position");
        if (rank == null) {
            rank = fallbackRank;
        }
        if (rank == null) {
            result.putNull("rank");
        } else {
            result.put("rank", rank);
        }
        return result;
    }

    private Long readUserId(JsonNode entry) {
        if (entry == null || entry.isNull()) {
            return null;
        }
        for (String field : new String[]{"userId", "studentId", "id", "user_id"}) {
            JsonNode value = entry.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.canConvertToLong()) {
                long userId = value.asLong();
                return userId > 0 ? userId : null;
            }
            if (value.isTextual()) {
                try {
                    long userId = Long.parseLong(value.asText());
                    return userId > 0 ? userId : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Integer readInt(JsonNode entry, String... fields) {
        if (entry == null || entry.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = entry.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.canConvertToInt()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private void putProgressPercent(ObjectNode result, JsonNode entry) {
        JsonNode progress = firstPresent(entry, "progressPercent", "progress", "percent", "coursePercent", "completionPercent");
        if (progress == null || progress.isNull()) {
            result.put("progressPercent", 0);
            return;
        }
        if (progress.isNumber()) {
            result.set("progressPercent", progress);
            return;
        }
        if (progress.isTextual()) {
            try {
                result.put("progressPercent", Double.parseDouble(progress.asText()));
                return;
            } catch (NumberFormatException ignored) {
                // Fall through to default value.
            }
        }
        result.put("progressPercent", 0);
    }

    private JsonNode firstPresent(JsonNode node, String... fields) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            if (node.has(field)) {
                return node.get(field);
            }
        }
        return null;
    }

    private void putNullableText(ObjectNode target, String targetField, JsonNode source, String sourceField) {
        String value = source == null ? null : textOrNull(source, sourceField);
        if (value == null) {
            target.putNull(targetField);
        } else {
            target.put(targetField, value);
        }
    }

    private JsonNode avatarUrlNode(String avatarUrl, HttpServletRequest request) {
        if (avatarUrl == null) {
            return objectMapper.nullNode();
        }
        String trimmed = avatarUrl.trim();
        if (trimmed.isEmpty()) {
            return objectMapper.nullNode();
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return objectMapper.getNodeFactory().textNode(trimmed);
        }
        String publicUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(trimmed.startsWith("/") ? trimmed : "/" + trimmed)
                .replaceQuery(null)
                .build()
                .toUriString();
        return objectMapper.getNodeFactory().textNode(publicUrl);
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private void addTeacherOwnedCourses(
            HttpServletRequest request,
            String authorization,
            List<ObjectNode> mergedItems,
            Set<Long> addedCourseIds
    ) {
        Long currentUserId = extractUserIdFromToken(request);
        if (currentUserId == null) {
            return;
        }

        ResponseEntity<byte[]> courseListResponse;
        try {
            courseListResponse = courseWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/admin/courses")
                            .queryParam("page", 0)
                            .queryParam("size", 100)
                            .queryParam("createdByUserId", currentUserId)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchangeToMono(res -> res.toEntity(byte[].class))
                    .block();
        } catch (Exception ex) {
            return;
        }

        if (courseListResponse == null || !courseListResponse.getStatusCode().is2xxSuccessful()) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(courseListResponse.getBody());
            JsonNode courses = root.path("content");
            if (!courses.isArray()) {
                return;
            }
            for (JsonNode courseRaw : courses) {
                long courseId = courseRaw.path("id").asLong(-1);
                if (courseId <= 0 || addedCourseIds.contains(courseId)) {
                    continue;
                }

                ObjectNode course = objectMapper.createObjectNode();
                copyTeacherCourseFields(courseRaw, course);

                ObjectNode merged = objectMapper.createObjectNode();
                merged.set("course", course);
                merged.putNull("progressPercent");
                merged.putNull("status");
                merged.putNull("nextItemId");
                merged.put("relation", "TEACHER");
                mergedItems.add(merged);
                addedCourseIds.add(courseId);
            }
        } catch (Exception ignored) {
            // Teacher course enrichment is best-effort and must not break enrolled courses.
        }
    }

    private Long extractUserIdFromToken(HttpServletRequest request) {
        String authorization = resolveAuthorization(request);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        String[] parts = token.split("\\.");
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

    private void copyTeacherCourseFields(JsonNode source, ObjectNode target) {
        copyIfPresent(source, target, "id");
        copyIfPresent(source, target, "slug");
        copyIfPresent(source, target, "title");
        copyIfPresent(source, target, "shortDescription");
        copyIfPresent(source, target, "difficulty");
        copyIfPresent(source, target, "status");
        copyIfPresent(source, target, "accessType");
        copyIfPresent(source, target, "enrollmentEnabled");
        copyIfPresent(source, target, "coverImageUrl");
        copyIfPresent(source, target, "estimatedMinutes");
        copyIfPresent(source, target, "createdByUserId");
        copyIfPresent(source, target, "createdAt");
        copyIfPresent(source, target, "updatedAt");
        copyIfPresent(source, target, "publishedAt");
        copyIfPresent(source, target, "submittedForReviewAt");
        copyIfPresent(source, target, "reviewedAt");
        copyIfPresent(source, target, "reviewedByUserId");
        copyIfPresent(source, target, "reviewComment");
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
                copyIfPresent(module, mergedModule, "deadlineType");
                copyIfPresent(module, mergedModule, "deadlineAt");
                copyIfPresent(module, mergedModule, "timeLimitMinutes");

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

    private ObjectNode mergeLearningItem(JsonNode course, JsonNode itemContent, JsonNode learningItem) {
        ObjectNode merged = objectMapper.createObjectNode();

        ObjectNode courseNode = objectMapper.createObjectNode();
        copyIfPresent(course, courseNode, "id");
        copyIfPresent(course, courseNode, "slug");
        copyIfPresent(course, courseNode, "title");
        merged.set("course", courseNode);

        ObjectNode itemNode = objectMapper.createObjectNode();
        itemNode.set("id", itemContent.path("itemId"));
        copyIfPresent(itemContent, itemNode, "title");
        copyIfPresent(itemContent, itemNode, "itemType");
        copyIfPresent(itemContent, itemNode, "statement");
        copyIfPresent(itemContent, itemNode, "contentBlocks");
        copyIfPresent(itemContent, itemNode, "hints");
        copyIfPresent(itemContent, itemNode, "options");
        copyIfPresent(itemContent, itemNode, "starterCode");
        copyIfPresent(itemContent, itemNode, "language");
        merged.set("item", itemNode);

        if (learningItem.has("progress")) {
            merged.set("progress", learningItem.get("progress"));
        } else {
            merged.set("progress", objectMapper.createObjectNode());
        }
        if (learningItem.has("navigation")) {
            merged.set("navigation", learningItem.get("navigation"));
        } else {
            merged.set("navigation", objectMapper.createObjectNode());
        }

        return merged;
    }
}
