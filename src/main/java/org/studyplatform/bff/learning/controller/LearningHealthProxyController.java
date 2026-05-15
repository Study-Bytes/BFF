package org.studyplatform.bff.learning.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
public class LearningHealthProxyController {

    private final WebClient learningWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public LearningHealthProxyController(
            @Qualifier("learningWebClient")
            WebClient learningWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.learningWebClient = learningWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @GetMapping("/learning-service/health")
    public ResponseEntity<byte[]> health(HttpServletRequest request) {
        return proxyExchangeService.exchange(request, learningWebClient, "/actuator/health");
    }
}
