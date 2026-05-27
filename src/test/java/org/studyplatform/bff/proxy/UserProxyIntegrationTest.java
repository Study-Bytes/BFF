package org.studyplatform.bff.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.io.ByteArrayResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserProxyIntegrationTest {

    private static HttpServer upstream;
    private static final Path avatarStorageDir;
    private static final List<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();

    static {
        try {
            avatarStorageDir = Files.createTempDirectory("bff-avatar-test-");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create avatar test storage directory", ex);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        startUpstream();
        String baseUrl = "http://localhost:" + upstream.getAddress().getPort();

        registry.add("svc.user.base-url", () -> baseUrl);
        registry.add("svc.course.base-url", () -> baseUrl);
        registry.add("bff.auth.cookies.secure", () -> false);
        registry.add("bff.avatar.storage-dir", () -> avatarStorageDir.toString());
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @BeforeEach
    void clearCapturedRequests() {
        capturedRequests.clear();
    }

    @Test
    void loginForwardsBodyAndSetsAuthCookies() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"email\":\"user@example.com\",\"password\":\"securePassword123\"}",
                headers
        );

        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/login", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotNull();
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie).contains("access_token="));
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie).contains("refresh_token="));
        assertThat(lastRequest().method()).isEqualTo("POST");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/auth/login");
        assertThat(lastRequest().body()).contains("securePassword123");
    }

    @Test
    void refreshUsesRefreshCookieWhenBodyIsMissing() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=cookie-refresh-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>("", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/auth/refresh");
        assertThat(lastRequest().body()).contains("cookie-refresh-token");
    }

    @Test
    void logoutUsesAccessCookieClearsCookiesAndReturnsSuccessPayload() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=access-cookie-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>("", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"success\":true");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/auth/logout");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-cookie-token");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie).contains("access_token="));
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie).contains("refresh_token="));
    }

    @Test
    void currentUserRouteMapsToUsersMe() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/users/me");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-token");
    }

    @Test
    void meSettingsRouteMapsToUsersMeSettings() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");

        ResponseEntity<String> getResponse = restTemplate.exchange(
                "/api/v1/me/settings",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/users/me/settings");

        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> putRequest = new HttpEntity<>(
                "{\"fullName\":\"Updated\",\"preferredLocale\":\"ru\",\"avatarUrl\":null,\"bio\":\"bio\"}",
                headers
        );
        ResponseEntity<String> putResponse = restTemplate.exchange(
                "/api/v1/me/settings",
                HttpMethod.PUT,
                putRequest,
                String.class
        );
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/users/me/settings");
        assertThat(lastRequest().method()).isEqualTo("PUT");
    }

    @Test
    void avatarUploadStoresFileAndUpdatesCurrentUserSettings() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/me/avatar",
                HttpMethod.POST,
                new HttpEntity<>(multipartFile("avatar.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3}), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"avatarUrl\"");
        assertThat(response.getBody()).contains("/api/v1/avatar-files/");
        assertThat(capturedRequests).hasSize(2);
        assertThat(capturedRequests.get(0).path()).isEqualTo("/api/v1/users/me");
        assertThat(capturedRequests.get(0).authorization()).isEqualTo("Bearer access-token");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/users/me/settings");
        assertThat(lastRequest().method()).isEqualTo("PUT");
        assertThat(lastRequest().body()).contains("\"avatarUrl\":\"http://localhost:");
        assertThat(lastRequest().body()).contains("/api/v1/avatar-files/");
    }

    @Test
    void avatarUploadPrefersAccessCookieOverAuthorizationHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("stale-header-token");
        headers.add(HttpHeaders.COOKIE, "access_token=access-cookie-token");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/me/avatar",
                HttpMethod.POST,
                new HttpEntity<>(multipartFile("avatar.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3}), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequests).hasSize(2);
        assertThat(capturedRequests.get(0).authorization()).isEqualTo("Bearer access-cookie-token");
        assertThat(capturedRequests.get(1).authorization()).isEqualTo("Bearer access-cookie-token");
    }

    @Test
    void avatarUploadRejectsUnsupportedMediaType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/me/avatar",
                HttpMethod.POST,
                new HttpEntity<>(multipartFile("avatar.txt", MediaType.TEXT_PLAIN_VALUE, "bad".getBytes(StandardCharsets.UTF_8)), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"");
    }

    @Test
    void avatarUploadRejectsTooLargeFile() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/me/avatar",
                HttpMethod.POST,
                new HttpEntity<>(multipartFile("avatar.png", MediaType.IMAGE_PNG_VALUE, new byte[(int) (5L * 1024L * 1024L + 1L)]), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
    }

    @Test
    void avatarUploadRequiresAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/me/avatar",
                HttpMethod.POST,
                new HttpEntity<>(multipartFile("avatar.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3}), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalUsersRoutesAreNotExposed() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/users/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void teacherRequestEndpointsAreForwardedToUserService() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/v1/teacher-requests",
                new HttpEntity<>("{\"motivation\":\"m\",\"experience\":\"e\",\"preferredTopics\":[\"Java\"]}", headers),
                String.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/teacher-requests");

        ResponseEntity<String> mineResponse = restTemplate.exchange(
                "/api/v1/teacher-requests/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(mineResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/teacher-requests/me");

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/teacher-requests?status=PENDING&page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/teacher-requests");
        assertThat(lastRequest().query()).isEqualTo("status=PENDING&page=0&size=20");

        ResponseEntity<String> approveResponse = restTemplate.exchange(
                "/api/v1/admin/teacher-requests/10/approve",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                String.class
        );
        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/teacher-requests/10/approve");

        ResponseEntity<String> rejectResponse = restTemplate.exchange(
                "/api/v1/admin/teacher-requests/10/reject",
                HttpMethod.POST,
                new HttpEntity<>("{\"reviewComment\":\"Need more details\"}", headers),
                String.class
        );
        assertThat(rejectResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/teacher-requests/10/reject");
    }

    @Test
    void teacherCoursesListIsForwardedToAdminCoursesWithoutBffFiltering() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/teacher/courses?status=DRAFT&page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().method()).isEqualTo("GET");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/courses");
        assertThat(lastRequest().query()).isEqualTo("status=DRAFT&page=0&size=20");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-token");
    }

    @Test
    void teacherCreateCourseInjectsCreatedByUserIdFromJwtSub() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("e30.eyJzdWIiOiIyIn0.signature");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/teacher/courses",
                HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"Java Core\"}", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/courses");
        assertThat(lastRequest().body()).contains("\"createdByUserId\":2");
    }

    @Test
    void teacherCourseProxyPrefersAccessCookieWhenAuthorizationIsNotJwt() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer token");
        headers.add(HttpHeaders.COOKIE, "access_token=header.payload.signature");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/teacher/courses",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/courses");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer header.payload.signature");
    }

    @Test
    void teacherItemEndpointsMapToAdminCourseItemsAndWrapContentBlocksBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");

        ResponseEntity<String> itemResponse = restTemplate.exchange(
                "/api/v1/teacher/items/42",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(itemResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().method()).isEqualTo("GET");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/course-items/42");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-token");

        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = """
                [{"type":"TEXT","content":"intro","orderIndex":0}]
                """;
        ResponseEntity<String> contentBlocksResponse = restTemplate.exchange(
                "/api/v1/teacher/items/42/content-blocks",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(contentBlocksResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().method()).isEqualTo("PUT");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/course-items/42/content-blocks");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-token");
        assertThat(lastRequest().body()).contains("\"contentBlocks\":[");
        assertThat(lastRequest().body()).contains("\"type\":\"TEXT\"");
    }

    @Test
    void teacherItemTestCasesArrayBodyIsWrappedForCourseServiceContract() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = """
                [{"testKey":"hidden-1","orderIndex":0,"visibility":"OPEN","inputData":"1","expectedOutput":"1"}]
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/teacher/items/42/test-cases",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().method()).isEqualTo("PUT");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/course-items/42/test-cases");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-token");
        assertThat(lastRequest().body()).contains("\"testCases\":[");
        assertThat(lastRequest().body()).contains("\"testKey\":\"hidden-1\"");
    }

    @Test
    void teacherItemHintsArrayBodyIsWrappedForCourseServiceContract() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = """
                [{"orderIndex":0,"text":"Think about input parsing."}]
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/teacher/items/42/hints",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().method()).isEqualTo("PUT");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/course-items/42/hints");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-token");
        assertThat(lastRequest().body()).contains("\"hints\":[");
        assertThat(lastRequest().body()).contains("\"text\":\"Think about input parsing.\"");
    }

    @Test
    void teacherItemOptionsArrayBodyIsWrappedForCourseServiceContract() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("access-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = """
                [{"orderIndex":0,"label":"A","text":"Option text","correct":true,"explanation":"Because it is correct."}]
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/teacher/items/42/options",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().method()).isEqualTo("PUT");
        assertThat(lastRequest().path()).isEqualTo("/api/v1/admin/course-items/42/options");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer access-token");
        assertThat(lastRequest().body()).contains("\"options\":[");
        assertThat(lastRequest().body()).contains("\"text\":\"Option text\"");
    }

    @Test
    void defaultLocaleResolvesFromAccountSettingThenAcceptLanguageThenFallback() {
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth("access-token");
        ResponseEntity<String> fromAccount = restTemplate.exchange(
                "/api/v1/i18n/default-locale",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                String.class
        );
        assertThat(fromAccount.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fromAccount.getBody()).contains("\"locale\":\"ru\"");
        assertThat(fromAccount.getBody()).contains("\"source\":\"ACCOUNT_SETTING\"");

        HttpHeaders acceptLanguageHeaders = new HttpHeaders();
        acceptLanguageHeaders.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
        ResponseEntity<String> fromAcceptLanguage = restTemplate.exchange(
                "/api/v1/i18n/default-locale",
                HttpMethod.GET,
                new HttpEntity<>(acceptLanguageHeaders),
                String.class
        );
        assertThat(fromAcceptLanguage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fromAcceptLanguage.getBody()).contains("\"locale\":\"en\"");
        assertThat(fromAcceptLanguage.getBody()).contains("\"source\":\"ACCEPT_LANGUAGE\"");

        HttpHeaders unsupportedLanguageHeaders = new HttpHeaders();
        unsupportedLanguageHeaders.set(HttpHeaders.ACCEPT_LANGUAGE, "de-DE,de;q=0.9");
        ResponseEntity<String> fallback = restTemplate.exchange(
                "/api/v1/i18n/default-locale",
                HttpMethod.GET,
                new HttpEntity<>(unsupportedLanguageHeaders),
                String.class
        );
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fallback.getBody()).contains("\"locale\":\"ru\"");
        assertThat(fallback.getBody()).contains("\"source\":\"FALLBACK\"");
    }

    @Test
    void registerTeacherRequestOrchestratesRegisterSettingsAndTeacherRequestCreation() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = """
                {
                  "fullName":"Teacher User",
                  "email":"teacher@example.com",
                  "password":"password123",
                  "preferredLocale":"en",
                  "motivation":"I want to create Java courses.",
                  "experience":"3 years of Java backend experience.",
                  "portfolioUrl":"https://example.com",
                  "preferredTopics":["Java","Spring Boot"]
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register-teacher-request",
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"teacherRequest\"");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie).contains("access_token="));
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie).contains("refresh_token="));
        assertThat(capturedRequests).anySatisfy(req -> assertThat(req.path()).isEqualTo("/api/v1/auth/register"));
        assertThat(capturedRequests).anySatisfy(req -> assertThat(req.path()).isEqualTo("/api/v1/users/me/settings"));
        assertThat(capturedRequests).anySatisfy(req -> assertThat(req.path()).isEqualTo("/api/v1/teacher-requests"));
    }

    @Test
    void jwksEndpointIsProxied() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/auth/.well-known/jwks.json", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastRequest().path()).isEqualTo("/api/v1/auth/.well-known/jwks.json");
    }

    private static CapturedRequest lastRequest() {
        return capturedRequests.get(capturedRequests.size() - 1);
    }

    private MultiValueMap<String, Object> multipartFile(String fileName, String contentType, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }, fileHeaders));
        return body;
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
        capturedRequests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE),
                exchange.getRequestHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE),
                body
        ));

        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String response = "{\"path\":\"" + path + "\"}";

        if ("/api/v1/auth/login".equals(path)) {
            response = "{\"user\":{\"id\":2,\"email\":\"test2@mail.com\",\"fullName\":\"Test User\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":null,\"bio\":null,\"preferredLocale\":\"ru\"},\"accessToken\":\"token\",\"refreshToken\":\"refresh\",\"tokenType\":\"Bearer\",\"expiresIn\":900}";
        } else if ("/api/v1/auth/register".equals(path)) {
            response = "{\"user\":{\"id\":3,\"email\":\"teacher@example.com\",\"fullName\":\"Teacher User\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":null,\"bio\":null,\"preferredLocale\":\"ru\"},\"accessToken\":\"register-access\",\"refreshToken\":\"register-refresh\",\"tokenType\":\"Bearer\",\"expiresIn\":900}";
        } else if ("/api/v1/auth/refresh".equals(path)) {
            response = "{\"user\":{\"id\":2,\"email\":\"test2@mail.com\",\"fullName\":\"Test User\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":null,\"bio\":null,\"preferredLocale\":\"ru\"},\"accessToken\":\"refreshed-access\",\"refreshToken\":\"refreshed-refresh\",\"tokenType\":\"Bearer\",\"expiresIn\":900}";
        } else if ("/api/v1/auth/logout".equals(path)) {
            status = 204;
            response = "";
        } else if ("/api/v1/users/me".equals(path)) {
            if (exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION) == null) {
                status = 401;
                response = "{\"message\":\"Unauthorized\"}";
            } else {
                response = "{\"id\":2,\"email\":\"test2@mail.com\",\"fullName\":\"Test User\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":null,\"bio\":null,\"preferredLocale\":\"ru\"}";
            }
        } else if ("/api/v1/users/me/settings".equals(path)) {
            if (body.contains("/api/v1/avatar-files/")) {
                int start = body.indexOf("\"avatarUrl\":\"") + "\"avatarUrl\":\"".length();
                int end = body.indexOf('"', start);
                String avatarUrl = body.substring(start, end);
                response = "{\"id\":2,\"email\":\"test2@mail.com\",\"fullName\":\"Test User\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":\"" + avatarUrl + "\",\"bio\":null,\"preferredLocale\":\"ru\"}";
            } else {
                response = "{\"id\":2,\"email\":\"test2@mail.com\",\"fullName\":\"Updated User\",\"role\":\"STUDENT\",\"status\":\"ACTIVE\",\"avatarUrl\":null,\"bio\":\"bio\",\"preferredLocale\":\"ru\"}";
            }
        } else if ("/api/v1/teacher-requests".equals(path)) {
            status = 201;
            response = "{\"id\":10,\"userId\":2,\"status\":\"PENDING\",\"motivation\":\"m\",\"experience\":\"e\",\"portfolioUrl\":null,\"preferredTopics\":[\"Java\"],\"reviewComment\":null,\"createdAt\":\"2026-05-17T12:00:00Z\",\"reviewedAt\":null,\"reviewedByUserId\":null}";
        } else if ("/api/v1/teacher-requests/me".equals(path)) {
            response = "{\"id\":10,\"userId\":2,\"status\":\"PENDING\",\"motivation\":\"m\",\"experience\":\"e\",\"portfolioUrl\":null,\"preferredTopics\":[\"Java\"],\"reviewComment\":null,\"createdAt\":\"2026-05-17T12:00:00Z\",\"reviewedAt\":null,\"reviewedByUserId\":null}";
        } else if ("/api/v1/admin/teacher-requests".equals(path)) {
            response = "{\"items\":[{\"id\":10,\"userId\":2,\"status\":\"PENDING\",\"motivation\":\"m\",\"experience\":\"e\",\"portfolioUrl\":null,\"preferredTopics\":[\"Java\"],\"reviewComment\":null,\"createdAt\":\"2026-05-17T12:00:00Z\",\"reviewedAt\":null,\"reviewedByUserId\":null}],\"page\":0,\"size\":20,\"totalItems\":1,\"totalPages\":1}";
        } else if ("/api/v1/admin/teacher-requests/10/approve".equals(path)) {
            response = "{\"id\":10,\"userId\":2,\"status\":\"APPROVED\",\"motivation\":\"m\",\"experience\":\"e\",\"portfolioUrl\":null,\"preferredTopics\":[\"Java\"],\"reviewComment\":null,\"createdAt\":\"2026-05-17T12:00:00Z\",\"reviewedAt\":\"2026-05-17T13:00:00Z\",\"reviewedByUserId\":99}";
        } else if ("/api/v1/admin/teacher-requests/10/reject".equals(path)) {
            response = "{\"id\":10,\"userId\":2,\"status\":\"REJECTED\",\"motivation\":\"m\",\"experience\":\"e\",\"portfolioUrl\":null,\"preferredTopics\":[\"Java\"],\"reviewComment\":\"Need more details\",\"createdAt\":\"2026-05-17T12:00:00Z\",\"reviewedAt\":\"2026-05-17T13:00:00Z\",\"reviewedByUserId\":99}";
        } else if ("/api/v1/auth/.well-known/jwks.json".equals(path)) {
            response = "{\"keys\":[]}";
        } else if ("/api/v1/courses".equals(path)) {
            response = "{\"courses\":[]}";
        } else if ("/api/v1/admin/courses".equals(path)) {
            if ("POST".equals(exchange.getRequestMethod())) {
                if (body.contains("\"createdByUserId\"")) {
                    status = 201;
                    response = "{\"id\":101}";
                } else {
                    status = 400;
                    response = "{\"message\":\"createdByUserId: не должно равняться null\"}";
                }
            } else {
                response = "{\"adminCourses\":[]}";
            }
        } else if ("/health".equals(path)) {
            response = "{\"status\":\"UP\",\"service\":\"UserService\",\"timestamp\":\"2026-05-13T12:00:00Z\"}";
        } else if ("/ready".equals(path)) {
            response = "{\"status\":\"UP\"}";
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(
            String method,
            String path,
            String query,
            String authorization,
            String cookie,
            String acceptLanguage,
            String body
    ) {
    }
}
