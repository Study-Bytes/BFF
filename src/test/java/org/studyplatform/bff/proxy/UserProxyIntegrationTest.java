package org.studyplatform.bff.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
class UserProxyIntegrationTest {

    private static HttpServer upstream;
    private static volatile CapturedRequest lastRequest;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        startUpstream();
        String baseUrl = "http://localhost:" + upstream.getAddress().getPort();

        registry.add("svc.user.base-url", () -> baseUrl);
        registry.add("svc.course.base-url", () -> baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void loginForwardsBodyToUserService() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"email\":\"user@example.com\",\"password\":\"securePassword123\"}",
                headers
        );

        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/login", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"accessToken\":\"token\"");
        assertThat(lastRequest.method()).isEqualTo("POST");
        assertThat(lastRequest.path()).isEqualTo("/api/v1/auth/login");
        assertThat(lastRequest.body()).contains("securePassword123");
    }

    @Test
    void userMeForwardsAuthorizationHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"role\":\"STUDENT\"");
        assertThat(lastRequest.path()).isEqualTo("/api/v1/users/me");
        assertThat(lastRequest.authorization()).isEqualTo("Bearer access-token");
    }

    @Test
    void healthForwardsToUserServiceHealthEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"service\":\"UserService\"");
        assertThat(lastRequest.path()).isEqualTo("/health");
    }

    @Test
    void jwksEndpointIsProxiedSeparatelyFromGenericProxy() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/auth/.well-known/jwks.json",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"keys\"");
        assertThat(lastRequest.path()).isEqualTo("/api/v1/auth/.well-known/jwks.json");
    }

    @Test
    void publicCourseCatalogIsForwardedToCourseService() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/courses", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"courses\"");
        assertThat(lastRequest.path()).isEqualTo("/api/v1/courses");
    }

    @Test
    void adminCourseRequestForwardsAuthorizationHeaderToCourseService() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("teacher-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/courses",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"adminCourses\"");
        assertThat(lastRequest.path()).isEqualTo("/api/v1/admin/courses");
        assertThat(lastRequest.authorization()).isEqualTo("Bearer teacher-token");
    }

    @Test
    void courseReadinessIsForwardedToCourseService() {
        ResponseEntity<String> response = restTemplate.getForEntity("/course-service/ready", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(lastRequest.path()).isEqualTo("/ready");
    }

    @Test
    void internalCourseEndpointsAreNotExposedByBff() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/internal/course-items/1/execution-package",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void methodErrorsReturnStructuredJson() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/courses",
                HttpEntity.EMPTY,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
        assertThat(response.getBody()).contains("\"path\":\"/api/v1/courses\"");
    }

    private static void startUpstream() throws IOException {
        if (upstream != null) {
            return;
        }

        upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/", UserProxyIntegrationTest::handle);
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

        if ("/api/v1/auth/login".equals(path)) {
            response = "{\"accessToken\":\"token\",\"refreshToken\":\"refresh\",\"tokenType\":\"Bearer\",\"expiresIn\":900}";
        } else if ("/api/v1/users/me".equals(path)) {
            response = "{\"id\":2,\"email\":\"test2@mail.com\",\"fullName\":\"Test User\",\"role\":\"STUDENT\"}";
        } else if ("/health".equals(path)) {
            response = "{\"status\":\"UP\",\"service\":\"UserService\",\"timestamp\":\"2026-05-13T12:00:00Z\"}";
        } else if ("/api/v1/auth/.well-known/jwks.json".equals(path)) {
            response = "{\"keys\":[]}";
        } else if ("/api/v1/courses".equals(path)) {
            response = "{\"courses\":[]}";
        } else if ("/api/v1/admin/courses".equals(path)) {
            response = "{\"adminCourses\":[]}";
        } else if ("/ready".equals(path)) {
            response = "{\"status\":\"UP\"}";
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
