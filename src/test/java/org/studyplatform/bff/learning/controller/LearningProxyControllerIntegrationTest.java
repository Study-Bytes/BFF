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
        assertThat(lastRequest().body()).contains("\"sourceCode\":\"print(input())\"");
        assertThat(lastRequest().body()).contains("\"selectedOptionIds\":[]");
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
                exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"),
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
        } else if ("/api/v1/learn/courses/3/items/4/submissions".equals(path)) {
            response = "[{\"id\":500,\"itemId\":4,\"status\":\"ACCEPTED\",\"score\":100,\"passedTests\":2,\"totalTests\":2,\"createdAt\":\"2026-05-16T12:00:00Z\"},{\"id\":499,\"itemId\":4,\"status\":\"WRONG_ANSWER\",\"score\":50,\"passedTests\":1,\"totalTests\":2,\"createdAt\":\"2026-05-16T11:50:00Z\"}]";
        } else if ("/api/v1/learn/submissions/500".equals(path)) {
            response = "{\"id\":500,\"itemId\":100,\"status\":\"ACCEPTED\",\"score\":100,\"passedTests\":2,\"totalTests\":2,\"stdout\":\"3\\n\",\"stderr\":null,\"testResults\":[{\"testKey\":\"sample-1\",\"visibility\":\"OPEN\",\"passed\":true,\"actualOutput\":\"3\",\"message\":null,\"durationMs\":25,\"memoryMb\":32},{\"testKey\":\"hidden-1\",\"visibility\":\"HIDDEN\",\"passed\":true,\"actualOutput\":\"42\",\"message\":null,\"durationMs\":31,\"memoryMb\":35}],\"createdAt\":\"2026-05-16T12:00:00Z\"}";
        } else if ("/api/v1/learning/tasks/7/submissions".equals(path)) {
            status = 201;
            response = "{\"id\":3,\"taskId\":7,\"verdict\":\"OK\",\"passedTestsCount\":2,\"totalTestsCount\":2}";
        } else if ("/api/v1/learn/my-courses".equals(path)) {
            response = "[{\"courseId\":3,\"progressPercent\":0,\"status\":\"NOT_STARTED\",\"nextItemId\":null}]";
        } else if ("/api/v1/learn/courses/1".equals(path)) {
            response = "{\"courseId\":1,\"progressPercent\":35,\"enrollmentStatus\":\"IN_PROGRESS\",\"nextItemId\":100,\"items\":[{\"itemId\":100,\"completed\":false,\"locked\":false},{\"itemId\":101,\"completed\":true,\"locked\":false}]}";
        } else if ("/api/v1/learn/courses/1/items/100".equals(path)) {
            response = "{\"courseId\":1,\"itemId\":100,\"progress\":{\"status\":\"NOT_STARTED\",\"attemptsCount\":2,\"lastScore\":80},\"navigation\":{\"previousItemId\":null,\"nextItemId\":101}}";
        } else if ("/api/v1/courses/3".equals(path)) {
            response = "{\"id\":3,\"slug\":\"postman-enroll-check\",\"title\":\"Postman Enroll Check\",\"shortDescription\":\"Test course for checking enrollment.\",\"difficulty\":\"BEGINNER\",\"accessType\":\"PUBLIC\",\"enrollmentEnabled\":true,\"coverImageUrl\":null,\"estimatedMinutes\":60,\"description\":\"extra\"}";
        } else if ("/api/v1/courses/1".equals(path)) {
            response = "{\"id\":1,\"slug\":\"java-core\",\"title\":\"Java Core\",\"shortDescription\":\"Learn Java fundamentals through structured lessons and practice tasks.\",\"difficulty\":\"BEGINNER\",\"accessType\":\"PUBLIC\",\"enrollmentEnabled\":true,\"coverImageUrl\":\"string\",\"estimatedMinutes\":480,\"description\":\"Full course description.\",\"status\":\"DRAFT\",\"modules\":[{\"id\":10,\"title\":\"Java Basics\",\"orderIndex\":0,\"items\":[{\"id\":100,\"title\":\"Introduction to Java\",\"itemType\":\"THEORY\",\"orderIndex\":0,\"estimatedMinutes\":10},{\"id\":102,\"title\":\"Second item\",\"itemType\":\"THEORY\",\"orderIndex\":1,\"estimatedMinutes\":8}]}]}";
        } else if ("/api/v1/internal/course-items/100/content".equals(path)) {
            response = "{\"itemId\":100,\"moduleId\":10,\"courseId\":1,\"title\":\"Variables practice\",\"itemType\":\"THEORY\",\"statement\":\"Solve the task\",\"starterCode\":\"print(\\\"hello\\\")\",\"language\":\"python\",\"contentBlocks\":[{\"id\":1,\"blockType\":\"TEXT\",\"orderIndex\":0,\"title\":\"Theory\",\"textContent\":\"Read this explanation.\",\"url\":\"string\",\"language\":\"java\",\"metadataJson\":\"string\"}],\"hints\":[{\"id\":1,\"orderIndex\":0,\"text\":\"Think about input parsing.\"}],\"options\":[{\"id\":1,\"orderIndex\":0,\"label\":\"A\",\"text\":\"Option text\",\"selected\":false,\"correct\":false,\"explanation\":\"Explanation\"}]}";
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

    private record CapturedRequest(String method, String path, String authorization, String internalApiKey, String body) {
    }
}
