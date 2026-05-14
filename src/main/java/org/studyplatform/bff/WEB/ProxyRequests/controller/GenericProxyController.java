package org.studyplatform.bff.WEB.ProxyRequests.controller;

import org.studyplatform.bff.proxy.ProxyExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static java.util.Map.entry;

@RestController("webProxyController")
@RequestMapping("/api/v1web")
public class GenericProxyController {

    private final Map<String, WebClient> clients;
    private final ProxyExchangeService proxyExchangeService;

    public GenericProxyController(
            @Qualifier("userWebClient")
            WebClient userClient,
            @Qualifier("competitionWebClient")
            WebClient competitionClient,
            @Qualifier("feedbackWebClient")
            WebClient feedbackClient,
            @Qualifier("chatWebClient")
            WebClient chatClient,
            @Qualifier("engineWebClient")
            WebClient engineClient,
            @Qualifier("statisticWebClient")
            WebClient statisticClient,
            ProxyExchangeService proxyExchangeService
    ) {
        this.proxyExchangeService = proxyExchangeService;
        this.clients = Map.ofEntries(
                entry("feedback",    feedbackClient),
                entry("chats",       chatClient),
                entry("tournaments", competitionClient),
                entry("teams",       competitionClient),
                entry("tour",        engineClient),
                entry("matches",     engineClient),
                entry("bracket",     engineClient),
                entry("stats",       statisticClient),
                entry("admin",       feedbackClient)
        );
    }


    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest req) {
        String pathAfterPrefix = req.getRequestURI().replaceFirst("/api/v1web", "");
        String[] parts = pathAfterPrefix.split("/", 3);
        if (parts.length < 2) {
            return ResponseEntity.badRequest()
                    .body("Path is missing service alias".getBytes(StandardCharsets.UTF_8));
        }

        String alias = parts[1];
        WebClient client = clients.get(alias);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(("Unknown service: " + alias).getBytes(StandardCharsets.UTF_8));
        }

        String uri = proxyExchangeService.buildUpstreamUri(req, "/api/v1web");
        return proxyExchangeService.exchange(req, client, uri);
    }
}
