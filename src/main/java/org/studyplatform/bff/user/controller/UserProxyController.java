package org.studyplatform.bff.user.controller;

import org.studyplatform.bff.proxy.ProxyExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
@RequestMapping("/api/v1")
public class UserProxyController {

    private final WebClient userWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public UserProxyController(
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.userWebClient = userWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @GetMapping("/me")
    public ResponseEntity<byte[]> currentUser(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildCurrentUserUri(request)
        );
    }

    @PutMapping({"/me/profile", "/me/password"})
    public ResponseEntity<byte[]> updateCurrentUser(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildCurrentUserUri(request)
        );
    }

    @GetMapping("/users/me")
    public ResponseEntity<byte[]> me(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<byte[]> getById(@PathVariable String id, HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }
}
