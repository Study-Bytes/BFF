package org.studyplatform.bff.course.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
@RequestMapping("/api/v1/teacher")
public class CourseTeacherProxyController {

    private static final String TEACHER_PREFIX = "/api/v1/teacher";
    private static final String TEACHER_ITEMS_PREFIX = "/api/v1/teacher/items";
    private static final String ADMIN_PREFIX = "/api/v1/admin";
    private static final String ADMIN_COURSE_ITEMS_PREFIX = "/api/v1/admin/course-items";

    private final WebClient courseWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public CourseTeacherProxyController(
            @Qualifier("courseWebClient")
            WebClient courseWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.courseWebClient = courseWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxyTeacherRequest(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                courseWebClient,
                buildTeacherUpstreamUri(request)
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
}
