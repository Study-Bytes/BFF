package org.studyplatform.bff.learning.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningProxyController {

    private final WebClient learningWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public LearningProxyController(
            @Qualifier("learningWebClient")
            WebClient learningWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.learningWebClient = learningWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxyLearningRequest(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                learningWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }
}
