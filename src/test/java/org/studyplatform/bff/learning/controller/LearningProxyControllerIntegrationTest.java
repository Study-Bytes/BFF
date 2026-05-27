package org.studyplatform.bff.learning.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LearningProxyControllerIntegrationTest {

    private static HttpServer upstream;
    private static final List<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        startUpstream();
        String baseUrl = "http://localhost:" + upstream.getAddress().getPort();

        registry.add("svc.learning.base-url", () -> baseUrl);
        registry.add("svc.course.base-url", () -> baseUrl);
        registry.add("svc.user.base-url", () -> baseUrl);
        registry.add("svc.course.internal-api-key", () -> "dev-course-service-internal-key");
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void learningProgressRequestIsForwardedToLearningService() {
        capturedRequests.clear();
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/learning/course-enrollments/2/10",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"courseId\":10");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learning/course-enrollments/2/10");
    }

    @Test
    void learnEnrollEndpointIsForwardedToLearningService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/enroll",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"courseId\":3");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learn/courses/3/enroll");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
    }

    @Test
    void learnRunEndpointForwardsBodyAndAuthorizationToLearningService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = "{\"sourceCode\":\"print(input())\",\"sql\":null,\"selectedOptionIds\":[]}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/items/4/run",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"itemId\":4");
        assertThat(response.getBody()).contains("\"status\":\"ACCEPTED\"");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learn/courses/3/items/4/run");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
        assertThat(lastRequest().contentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(lastRequest().body()).contains("\"sourceCode\":\"print(input())\"");
        assertThat(lastRequest().body()).contains("\"selectedOptionIds\":[]");
    }

    @Test
    void learnSubmitEndpointForwardsBodyAndAuthorizationToLearningService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = "{\"sourceCode\":\"print(input())\",\"sql\":null,\"selectedOptionIds\":[]}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/items/4/submit",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"itemId\":4");
        assertThat(response.getBody()).contains("\"status\":\"ACCEPTED\"");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learn/courses/3/items/4/submit");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
        assertThat(lastRequest().contentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(lastRequest().body()).contains("\"sourceCode\":\"print(input())\"");
        assertThat(lastRequest().body()).contains("\"selectedOptionIds\":[]");
    }

    @Test
    void learnRunEndpointPreservesMeaningfulUpstream400Message() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = "{\"sourceCode\":\"trigger-400\",\"selectedOptionIds\":[]}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/items/5/run",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(response.getBody()).contains("\"message\":\"Некорректное тело запроса\"");
    }

    @Test
    void learnRunEndpointPreservesMeaningfulUpstream422Message() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = "{\"sourceCode\":\"trigger-422\",\"selectedOptionIds\":[]}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/items/5/run",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(response.getBody()).contains("\"message\":\"Runnable source is required\"");
    }

    @Test
    void learnItemSubmissionsEndpointForwardsAuthorizationToLearningService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/items/4/submissions",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":500");
        assertThat(response.getBody()).contains("\"itemId\":4");
        assertThat(response.getBody()).contains("\"status\":\"ACCEPTED\"");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learn/courses/3/items/4/submissions");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
    }

    @Test
    void learnCompleteEndpointForwardsAuthorizationToLearningService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/items/4/complete",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"completed\":true");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learn/courses/3/items/4/complete");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
    }

    @Test
    void learnModuleDeadlineStateEndpointForwardsQueryAndAuthorizationToLearningService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");

        ResponseEntity<String> response = restTemplate.exchange(
                URI.create("/api/v1/learn/courses/3/modules/10/deadline-state?deadlineAt=2026-06-01T23%3A59%3A00"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"courseId\":3");
        assertThat(response.getBody()).contains("\"moduleId\":10");
        assertThat(response.getBody()).contains("\"deadlineStatus\":\"COMPLETED_LATE\"");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learn/courses/3/modules/10/deadline-state");
        assertThat(lastRequest().query()).isEqualTo("deadlineAt=2026-06-01T23:59:00");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
    }

    @Test
    void learnSubmissionByIdEndpointForwardsAuthorizationToLearningService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/submissions/500",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":500");
        assertThat(response.getBody()).contains("\"itemId\":100");
        assertThat(response.getBody()).contains("\"status\":\"ACCEPTED\"");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learn/submissions/500");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
    }

    @Test
    void learningSubmissionForwardsBodyAndAuthorizationHeader() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"taskId\":7,\"language\":\"python\",\"sourceCode\":\"print(42)\",\"executionMode\":\"BATCH\"}",
                headers
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/learning/tasks/7/submissions",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"verdict\":\"OK\"");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/learning/tasks/7/submissions");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer student-token");
        assertThat(lastRequest().body()).contains("\"sourceCode\":\"print(42)\"");
    }

    @Test
    void learningHealthIsForwardedToActuatorHealth() {
        capturedRequests.clear();
        ResponseEntity<String> response = restTemplate.getForEntity("/learning-service/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"service\":\"LearningService\"");
        assertThat(lastRequest().path()).isEqualTo("/actuator/health");
    }

    @Test
    void learnMyCoursesAggregatesLearningAndCourseData() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/my-courses",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"course\"");
        assertThat(response.getBody()).contains("\"id\":3");
        assertThat(response.getBody()).contains("\"title\":\"Postman Enroll Check\"");
        assertThat(response.getBody()).contains("\"progressPercent\":0");
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/learn/my-courses");
            assertThat(req.authorization()).isEqualTo("Bearer student-token");
        });
        assertThat(capturedRequests).anySatisfy(req -> assertThat(req.path()).isEqualTo("/api/v1/courses/3"));
    }

    @Test
    void learnMyCoursesAddsTeacherOwnedCoursesFromCourseService() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("e30.eyJzdWIiOiI1In0.signature");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/my-courses",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"relation\":\"TEACHER\"");
        assertThat(response.getBody()).contains("\"id\":2");
        assertThat(response.getBody()).contains("\"title\":\"SQL Course\"");
        assertThat(response.getBody()).contains("\"status\":\"DRAFT\"");
        assertThat(response.getBody()).contains("\"createdByUserId\":5");
        assertThat(response.getBody()).contains("\"progressPercent\":null");
        assertThat(response.getBody()).contains("\"nextItemId\":null");
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/admin/courses");
            assertThat(req.query()).isEqualTo("page=0&size=100&createdByUserId=5");
            assertThat(req.authorization()).isEqualTo("Bearer e30.eyJzdWIiOiI1In0.signature");
        });
    }

    @Test
    void learnMyCoursesKeepsLearnerItemWhenTeacherCourseDuplicatesEnrollment() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("e30.eyJzdWIiOiI2In0.signature");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/my-courses",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"relation\":\"LEARNER\"");
        assertThat(response.getBody()).contains("\"progressPercent\":0");
        assertThat(response.getBody()).doesNotContain("\"relation\":\"TEACHER\"");
        assertThat(response.getBody()).doesNotContain("\"createdByUserId\":6");
    }

    @Test
    void learnMyCoursesReturnsLearnerCoursesWhenTeacherCourseListIsForbidden() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("e30.eyJzdWIiOiI3In0.signature");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/my-courses",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"relation\":\"LEARNER\"");
        assertThat(response.getBody()).contains("\"id\":3");
        assertThat(response.getBody()).doesNotContain("\"relation\":\"TEACHER\"");
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/admin/courses");
            assertThat(req.query()).isEqualTo("page=0&size=100&createdByUserId=7");
        });
    }

    @Test
    void learnCourseLeaderboardEnrichesEntriesWithUserProfiles() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("e30.eyJzdWIiOiIyIn0.signature");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/3/leaderboard",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"courseId\":3");
        assertThat(response.getBody()).contains("\"userId\":2");
        assertThat(response.getBody()).contains("\"fullName\":\"Student Two\"");
        assertThat(response.getBody()).contains("\"avatarUrl\":\"http://localhost:");
        assertThat(response.getBody()).contains("/api/v1/avatar-files/student-two.png");
        assertThat(response.getBody()).contains("\"progressPercent\":85");
        assertThat(response.getBody()).contains("\"rank\":1");
        assertThat(response.getBody()).contains("\"currentUser\"");
        assertThat(response.getBody()).doesNotContain("student2@example.com");
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/learn/courses/3/leaderboard");
            assertThat(req.authorization()).isEqualTo("Bearer e30.eyJzdWIiOiIyIn0.signature");
        });
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/users/me");
            assertThat(req.authorization()).isEqualTo("Bearer e30.eyJzdWIiOiIyIn0.signature");
        });
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/users/3");
            assertThat(req.authorization()).isEqualTo("Bearer e30.eyJzdWIiOiIyIn0.signature");
        });
    }

    @Test
    void learnCourseAggregatesCourseAndLearningState() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/1",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":1");
        assertThat(response.getBody()).contains("\"slug\":\"java-core\"");
        assertThat(response.getBody()).contains("\"progressPercent\":35");
        assertThat(response.getBody()).contains("\"enrollmentStatus\":\"IN_PROGRESS\"");
        assertThat(response.getBody()).contains("\"nextItemId\":100");
        assertThat(response.getBody()).contains("\"deadlineType\":\"ABSOLUTE\"");
        assertThat(response.getBody()).contains("\"deadlineAt\":\"2026-06-01T23:59:00\"");
        assertThat(response.getBody()).contains("\"timeLimitMinutes\":null");
        assertThat(response.getBody()).contains("\"id\":100");
        assertThat(response.getBody()).contains("\"completed\":false");
        assertThat(response.getBody()).contains("\"locked\":false");
        assertThat(response.getBody()).contains("\"id\":102");
        assertThat(response.getBody()).contains("\"completed\":false");
        assertThat(response.getBody()).contains("\"locked\":false");
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/learn/courses/1");
            assertThat(req.authorization()).isEqualTo("Bearer student-token");
        });
        assertThat(capturedRequests).anySatisfy(req -> assertThat(req.path()).isEqualTo("/api/v1/courses/1"));
    }

    @Test
    void learnCourseItemAggregatesLearningAndCourseContent() {
        capturedRequests.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("student-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/learn/courses/1/items/100",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"course\"");
        assertThat(response.getBody()).contains("\"item\"");
        assertThat(response.getBody()).contains("\"slug\":\"java-core\"");
        assertThat(response.getBody()).contains("\"title\":\"Variables practice\"");
        assertThat(response.getBody()).contains("\"itemType\":\"THEORY\"");
        assertThat(response.getBody()).contains("\"progress\"");
        assertThat(response.getBody()).contains("\"attemptsCount\":2");
        assertThat(response.getBody()).contains("\"lastScore\":80");
        assertThat(response.getBody()).contains("\"navigation\"");
        assertThat(response.getBody()).contains("\"nextItemId\":101");
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/learn/courses/1/items/100");
            assertThat(req.authorization()).isEqualTo("Bearer student-token");
        });
        assertThat(capturedRequests).anySatisfy(req -> {
            assertThat(req.path()).isEqualTo("/api/v1/internal/course-items/100/content");
            assertThat(req.internalApiKey()).isEqualTo("dev-course-service-internal-key");
        });
        assertThat(capturedRequests).anySatisfy(req -> assertThat(req.path()).isEqualTo("/api/v1/courses/1"));
    }

    private static void startUpstream() throws IOException {
        if (upstream != null) {
            return;
        }

        upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/", LearningProxyControllerIntegrationTest::handle);
        upstream.start();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedRequests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"),
                exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                body
        ));

        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String response = "{\"path\":\"" + path + "\"}";

        if ("/api/v1/learning/course-enrollments/2/10".equals(path)) {
            response = "{\"id\":1,\"userId\":2,\"courseId\":10,\"status\":\"IN_PROGRESS\"}";
        } else if ("/api/v1/learn/courses/3/enroll".equals(path)) {
            response = "{\"courseId\":3,\"enrollmentStatus\":\"ENROLLED\"}";
        } else if ("/api/v1/learn/courses/3/items/4/run".equals(path)) {
            response = "{\"id\":500,\"itemId\":4,\"status\":\"ACCEPTED\",\"score\":100,\"passedTests\":2,\"totalTests\":2,\"stdout\":\"3\\n\",\"stderr\":null,\"testResults\":[{\"testKey\":\"sample-1\",\"visibility\":\"OPEN\",\"passed\":true,\"actualOutput\":\"3\",\"message\":null,\"durationMs\":25,\"memoryMb\":32}],\"createdAt\":\"2026-05-16T12:00:00Z\"}";
        } else if ("/api/v1/learn/courses/3/items/4/submit".equals(path)) {
            response = "{\"id\":500,\"itemId\":4,\"status\":\"ACCEPTED\",\"score\":100,\"passedTests\":2,\"totalTests\":2,\"stdout\":\"3\\n\",\"stderr\":null,\"testResults\":[{\"testKey\":\"sample-1\",\"visibility\":\"OPEN\",\"passed\":true,\"actualOutput\":\"3\",\"message\":null,\"durationMs\":25,\"memoryMb\":32},{\"testKey\":\"hidden-1\",\"visibility\":\"HIDDEN\",\"passed\":true,\"actualOutput\":\"42\",\"message\":null,\"durationMs\":31,\"memoryMb\":35}],\"createdAt\":\"2026-05-16T12:00:00Z\"}";
        } else if ("/api/v1/learn/courses/3/items/5/run".equals(path) && body.contains("trigger-400")) {
            status = 400;
            response = "{\"timestamp\":\"2026-05-18T21:49:28\",\"status\":400,\"error\":\"Bad Request\",\"message\":\"Некорректное тело запроса\",\"path\":\"/api/v1/learn/courses/3/items/5/run\"}";
        } else if ("/api/v1/learn/courses/3/items/5/run".equals(path) && body.contains("trigger-422")) {
            status = 422;
            response = "{\"timestamp\":\"2026-05-18T21:49:28\",\"status\":422,\"error\":\"Unprocessable Entity\",\"message\":\"Runnable source is required\",\"path\":\"/api/v1/learn/courses/3/items/5/run\"}";
        } else if ("/api/v1/learn/courses/3/items/4/submissions".equals(path)) {
            response = "[{\"id\":500,\"itemId\":4,\"status\":\"ACCEPTED\",\"score\":100,\"passedTests\":2,\"totalTests\":2,\"createdAt\":\"2026-05-16T12:00:00Z\"},{\"id\":499,\"itemId\":4,\"status\":\"WRONG_ANSWER\",\"score\":50,\"passedTests\":1,\"totalTests\":2,\"createdAt\":\"2026-05-16T11:50:00Z\"}]";
        } else if ("/api/v1/learn/courses/3/items/4/complete".equals(path)) {
            response = "{\"courseId\":3,\"itemId\":4,\"completed\":true}";
        } else if ("/api/v1/learn/courses/3/modules/10/deadline-state".equals(path)) {
            response = "{\"courseId\":3,\"moduleId\":10,\"deadlineAt\":\"2026-06-01T23:59:00\",\"moduleCompletedAt\":\"2026-06-02T10:15:00\",\"moduleCompletedBeforeDeadline\":false,\"deadlineStatus\":\"COMPLETED_LATE\",\"tasksCompletedBeforeDeadline\":[{\"taskId\":101,\"completedAt\":\"2026-05-30T18:45:00\"}],\"tasksCompletedAfterDeadline\":[{\"taskId\":102,\"completedAt\":\"2026-06-02T10:10:00\"}]}";
        } else if ("/api/v1/learn/submissions/500".equals(path)) {
            response = "{\"id\":500,\"itemId\":100,\"status\":\"ACCEPTED\",\"score\":100,\"passedTests\":2,\"totalTests\":2,\"stdout\":\"3\\n\",\"stderr\":null,\"testResults\":[{\"testKey\":\"sample-1\",\"visibility\":\"OPEN\",\"passed\":true,\"actualOutput\":\"3\",\"message\":null,\"durationMs\":25,\"memoryMb\":32},{\"testKey\":\"hidden-1\",\"visibility\":\"HIDDEN\",\"passed\":true,\"actualOutput\":\"42\",\"message\":null,\"durationMs\":31,\"memoryMb\":35}],\"createdAt\":\"2026-05-16T12:00:00Z\"}";
        } else if ("/api/v1/learning/tasks/7/submissions".equals(path)) {
            status = 201;
            response = "{\"id\":3,\"taskId\":7,\"verdict\":\"OK\",\"passedTestsCount\":2,\"totalTestsCount\":2}";
        } else if ("/api/v1/learn/my-courses".equals(path)) {
            response = "[{\"courseId\":3,\"progressPercent\":0,\"status\":\"NOT_STARTED\",\"nextItemId\":null}]";
        } else if ("/api/v1/learn/courses/3/leaderboard".equals(path)) {
            response = "{\"courseId\":3,\"top\":[{\"userId\":2,\"nickname\":\"student2@example.com\",\"progressPercent\":85,\"place\":1},{\"userId\":3,\"nickname\":\"student3@example.com\",\"progressPercent\":70,\"place\":2}],\"currentUser\":{\"userId\":2,\"nickname\":\"student2@example.com\",\"progressPercent\":85,\"place\":1}}";
        } else if ("/api/v1/learn/courses/1".equals(path)) {
            response = "{\"courseId\":1,\"progressPercent\":35,\"enrollmentStatus\":\"IN_PROGRESS\",\"nextItemId\":100,\"items\":[{\"itemId\":100,\"completed\":false,\"locked\":false},{\"itemId\":101,\"completed\":true,\"locked\":false}]}";
        } else if ("/api/v1/learn/courses/1/items/100".equals(path)) {
            response = "{\"courseId\":1,\"itemId\":100,\"progress\":{\"status\":\"NOT_STARTED\",\"attemptsCount\":2,\"lastScore\":80},\"navigation\":{\"previousItemId\":null,\"nextItemId\":101}}";
        } else if ("/api/v1/users/me".equals(path)) {
            response = "{\"id\":2,\"email\":\"student2@example.com\",\"fullName\":\"Student Two\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":\"/api/v1/avatar-files/student-two.png\",\"bio\":null,\"preferredLocale\":\"ru\"}";
        } else if ("/api/v1/users/3".equals(path)) {
            response = "{\"id\":3,\"email\":\"student3@example.com\",\"fullName\":\"Student Three\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":\"https://cdn.example.com/student-three.png\",\"bio\":null,\"preferredLocale\":\"ru\"}";
        } else if ("/api/v1/courses/3".equals(path)) {
            response = "{\"id\":3,\"slug\":\"postman-enroll-check\",\"title\":\"Postman Enroll Check\",\"shortDescription\":\"Test course for checking enrollment.\",\"difficulty\":\"BEGINNER\",\"accessType\":\"PUBLIC\",\"enrollmentEnabled\":true,\"coverImageUrl\":null,\"estimatedMinutes\":60,\"description\":\"extra\"}";
        } else if ("/api/v1/courses/1".equals(path)) {
            response = "{\"id\":1,\"slug\":\"java-core\",\"title\":\"Java Core\",\"shortDescription\":\"Learn Java fundamentals through structured lessons and practice tasks.\",\"difficulty\":\"BEGINNER\",\"accessType\":\"PUBLIC\",\"enrollmentEnabled\":true,\"coverImageUrl\":\"string\",\"estimatedMinutes\":480,\"description\":\"Full course description.\",\"status\":\"DRAFT\",\"modules\":[{\"id\":10,\"title\":\"Java Basics\",\"orderIndex\":0,\"deadlineType\":\"ABSOLUTE\",\"deadlineAt\":\"2026-06-01T23:59:00\",\"timeLimitMinutes\":null,\"items\":[{\"id\":100,\"title\":\"Introduction to Java\",\"itemType\":\"THEORY\",\"orderIndex\":0,\"estimatedMinutes\":10},{\"id\":102,\"title\":\"Second item\",\"itemType\":\"THEORY\",\"orderIndex\":1,\"estimatedMinutes\":8}]}]}";
        } else if ("/api/v1/internal/course-items/100/content".equals(path)) {
            response = "{\"itemId\":100,\"moduleId\":10,\"courseId\":1,\"title\":\"Variables practice\",\"itemType\":\"THEORY\",\"statement\":\"Solve the task\",\"starterCode\":\"print(\\\"hello\\\")\",\"language\":\"python\",\"contentBlocks\":[{\"id\":1,\"blockType\":\"TEXT\",\"orderIndex\":0,\"title\":\"Theory\",\"textContent\":\"Read this explanation.\",\"url\":\"string\",\"language\":\"java\",\"metadataJson\":\"string\"}],\"hints\":[{\"id\":1,\"orderIndex\":0,\"text\":\"Think about input parsing.\"}],\"options\":[{\"id\":1,\"orderIndex\":0,\"label\":\"A\",\"text\":\"Option text\",\"selected\":false,\"correct\":false,\"explanation\":\"Explanation\"}]}";
        } else if ("/api/v1/admin/courses".equals(path)) {
            String query = exchange.getRequestURI().getRawQuery();
            if ("page=0&size=100&createdByUserId=5".equals(query)) {
                response = "{\"content\":[{\"id\":2,\"slug\":\"sql-course\",\"title\":\"SQL Course\",\"shortDescription\":\"SQL from zero\",\"difficulty\":\"BEGINNER\",\"status\":\"DRAFT\",\"accessType\":\"PUBLIC\",\"enrollmentEnabled\":true,\"coverImageUrl\":\"https://example.com/sql.png\",\"estimatedMinutes\":180,\"createdByUserId\":5,\"createdAt\":\"2026-05-27T10:00:00Z\",\"updatedAt\":\"2026-05-27T10:30:00Z\"}],\"page\":0,\"size\":100,\"totalElements\":1,\"totalPages\":1}";
            } else if ("page=0&size=100&createdByUserId=6".equals(query)) {
                response = "{\"content\":[{\"id\":3,\"slug\":\"postman-enroll-check\",\"title\":\"Postman Enroll Check\",\"shortDescription\":\"Teacher duplicate\",\"difficulty\":\"BEGINNER\",\"status\":\"DRAFT\",\"accessType\":\"PUBLIC\",\"enrollmentEnabled\":true,\"coverImageUrl\":null,\"estimatedMinutes\":60,\"createdByUserId\":6}],\"page\":0,\"size\":100,\"totalElements\":1,\"totalPages\":1}";
            } else if ("page=0&size=100&createdByUserId=7".equals(query)) {
                status = 403;
                response = "{\"message\":\"Forbidden\"}";
            }
        } else if ("/actuator/health".equals(path)) {
            response = "{\"status\":\"UP\",\"service\":\"LearningService\"}";
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static CapturedRequest lastRequest() {
        return capturedRequests.get(capturedRequests.size() - 1);
    }

    private record CapturedRequest(String method, String path, String query, String authorization, String internalApiKey, String contentType, String body) {
    }
}
