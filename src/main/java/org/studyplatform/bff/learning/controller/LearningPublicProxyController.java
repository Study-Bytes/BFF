package org.studyplatform.bff.learning.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
@RequestMapping("/api/v1/learn")
public class LearningPublicProxyController {

    private final WebClient learningWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public LearningPublicProxyController(
            @Qualifier("learningWebClient")
            WebClient learningWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.learningWebClient = learningWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @GetMapping("/courses/{courseId}/modules/{moduleId}/deadline-state")
    public ResponseEntity<byte[]> proxyModuleDeadlineState(
            @PathVariable Long courseId,
            @PathVariable Long moduleId,
            @RequestParam String deadlineAt,
            HttpServletRequest request
    ) {
        String upstreamUri = "/api/v1/learn/courses/%d/modules/%d/deadline-state?deadlineAt=%s"
                .formatted(courseId, moduleId, deadlineAt);
        return proxyExchangeService.exchange(
                request,
                learningWebClient,
                upstreamUri
        );
    }

    @RequestMapping("/courses/**")
    public ResponseEntity<byte[]> proxyLearnRequest(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                learningWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    @RequestMapping("/submissions/**")
    public ResponseEntity<byte[]> proxyLearnSubmissionsRequest(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                learningWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }
}
