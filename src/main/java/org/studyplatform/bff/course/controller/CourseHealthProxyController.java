package org.studyplatform.bff.course.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
public class CourseHealthProxyController {

    private final WebClient courseWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public CourseHealthProxyController(
            @Qualifier("courseWebClient")
            WebClient courseWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.courseWebClient = courseWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @GetMapping("/course-service/health")
    public ResponseEntity<byte[]> health(HttpServletRequest request) {
        return proxyExchangeService.exchange(request, courseWebClient, "/health");
    }

    @GetMapping("/course-service/ready")
    public ResponseEntity<byte[]> ready(HttpServletRequest request) {
        return proxyExchangeService.exchange(request, courseWebClient, "/ready");
    }
}
