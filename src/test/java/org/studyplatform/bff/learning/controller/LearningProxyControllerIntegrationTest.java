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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LearningProxyControllerIntegrationTest {

    private static HttpServer upstream;
    private static volatile CapturedRequest lastRequest;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        startUpstream();
        String baseUrl = "http://localhost:" + upstream.getAddress().getPort();

        registry.add("svc.learning.base-url", () -> baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void learningProgressRequestIsForwardedToLearningService() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/learning/course-enrollments/2/10",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"courseId\":10");
        assertThat(lastRequest.path()).isEqualTo("/api/v1/learning/course-enrollments/2/10");
    }

    @Test
    void learningSubmissionForwardsBodyAndAuthorizationHeader() {
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
        assertThat(lastRequest.path()).isEqualTo("/api/v1/learning/tasks/7/submissions");
        assertThat(lastRequest.authorization()).isEqualTo("Bearer student-token");
        assertThat(lastRequest.body()).contains("\"sourceCode\":\"print(42)\"");
    }

    @Test
    void learningHealthIsForwardedToActuatorHealth() {
        ResponseEntity<String> response = restTemplate.getForEntity("/learning-service/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"service\":\"LearningService\"");
        assertThat(lastRequest.path()).isEqualTo("/actuator/health");
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
        lastRequest = new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                body
        );

        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String response = "{\"path\":\"" + path + "\"}";

        if ("/api/v1/learning/course-enrollments/2/10".equals(path)) {
            response = "{\"id\":1,\"userId\":2,\"courseId\":10,\"status\":\"IN_PROGRESS\"}";
        } else if ("/api/v1/learning/tasks/7/submissions".equals(path)) {
            status = 201;
            response = "{\"id\":3,\"taskId\":7,\"verdict\":\"OK\",\"passedTestsCount\":2,\"totalTestsCount\":2}";
        } else if ("/actuator/health".equals(path)) {
            response = "{\"status\":\"UP\",\"service\":\"LearningService\"}";
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }
}
