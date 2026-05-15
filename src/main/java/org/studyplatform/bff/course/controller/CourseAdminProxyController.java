package org.studyplatform.bff.course.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
@RequestMapping("/api/v1/admin")
public class CourseAdminProxyController {

    private final WebClient courseWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public CourseAdminProxyController(
            @Qualifier("courseWebClient")
            WebClient courseWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.courseWebClient = courseWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxyAdminRequest(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                courseWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }
}
