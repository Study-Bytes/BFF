package org.studyplatform.bff.user.controller;

import org.studyplatform.bff.proxy.ProxyExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthProxyController {

    private final WebClient userWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public AuthProxyController(
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.userWebClient = userWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @PostMapping({"/register", "/login", "/refresh", "/logout"})
    public ResponseEntity<byte[]> post(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<byte[]> jwks(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }
}
