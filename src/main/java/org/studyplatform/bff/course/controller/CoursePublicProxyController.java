package org.studyplatform.bff.course.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
@RequestMapping("/api/v1")
public class CoursePublicProxyController {

    private final WebClient courseWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public CoursePublicProxyController(
            @Qualifier("courseWebClient")
            WebClient courseWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.courseWebClient = courseWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @GetMapping("/courses")
    public ResponseEntity<byte[]> getCourses(HttpServletRequest request) {
        return proxy(request);
    }

    @GetMapping("/courses/{courseId}")
    public ResponseEntity<byte[]> getCourse(
            @PathVariable Long courseId,
            HttpServletRequest request
    ) {
        return proxy(request);
    }

    @GetMapping("/course-items/{itemId}")
    public ResponseEntity<byte[]> getCourseItemPreview(
            @PathVariable Long itemId,
            HttpServletRequest request
    ) {
        return proxy(request);
    }

    private ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                courseWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }
}
