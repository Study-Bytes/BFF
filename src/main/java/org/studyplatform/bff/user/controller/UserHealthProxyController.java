package org.studyplatform.bff.user.controller;

import org.studyplatform.bff.proxy.ProxyExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
public class UserHealthProxyController {

    private final WebClient userWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public UserHealthProxyController(
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.userWebClient = userWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @GetMapping("/health")
    public ResponseEntity<byte[]> health(HttpServletRequest request) {
        return proxyExchangeService.exchange(request, userWebClient, "/health");
    }
}
