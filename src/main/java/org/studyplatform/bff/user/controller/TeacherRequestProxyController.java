package org.studyplatform.bff.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.studyplatform.bff.proxy.ProxyExchangeService;

@RestController
@RequestMapping("/api/v1")
public class TeacherRequestProxyController {

    private final WebClient userWebClient;
    private final ProxyExchangeService proxyExchangeService;

    public TeacherRequestProxyController(
            @Qualifier("userWebClient")
            WebClient userWebClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.userWebClient = userWebClient;
        this.proxyExchangeService = proxyExchangeService;
    }

    @PostMapping("/teacher-requests")
    public ResponseEntity<byte[]> create(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    @GetMapping("/teacher-requests/me")
    public ResponseEntity<byte[]> mine(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    @GetMapping("/admin/teacher-requests")
    public ResponseEntity<byte[]> adminList(HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    @PostMapping("/admin/teacher-requests/{requestId}/approve")
    public ResponseEntity<byte[]> approve(@PathVariable String requestId, HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }

    @PostMapping("/admin/teacher-requests/{requestId}/reject")
    public ResponseEntity<byte[]> reject(@PathVariable String requestId, HttpServletRequest request) {
        return proxyExchangeService.exchange(
                request,
                userWebClient,
                proxyExchangeService.buildAuthOrUsersUri(request)
        );
    }
}
