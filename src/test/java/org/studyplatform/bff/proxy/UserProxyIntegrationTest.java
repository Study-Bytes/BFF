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
        registry.add("svc.competition.base-url", () -> baseUrl);
        registry.add("svc.feedback.base-url", () -> baseUrl);
        registry.add("svc.chat.base-url", () -> baseUrl);
        registry.add("svc.statistic.base-url", () -> baseUrl);
        registry.add("svc.engine.base-url", () -> baseUrl);
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
    void webProxyPreservesUpstreamStatusCode() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1web/tournaments/status-check",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.I_AM_A_TEAPOT);
        assertThat(response.getBody()).contains("teapot");
        assertThat(lastRequest.path()).isEqualTo("/api/v1/tournaments/status-check");
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
        } else if ("/api/v1web/tournaments/status-check".equals(path)
                || "/api/v1/tournaments/status-check".equals(path)) {
            status = 418;
            response = "{\"error\":\"teapot\"}";
        } else if ("/api/v1/auth/.well-known/jwks.json".equals(path)) {
            response = "{\"keys\":[]}";
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
