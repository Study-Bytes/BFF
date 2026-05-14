
package org.studyplatform.bff.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean("userWebClient")
    public WebClient userWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.user.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    @Bean("competitionWebClient")
    public WebClient competitionWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.competition.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    @Bean("feedbackWebClient")
    public WebClient feedbackWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.feedback.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    @Bean("chatWebClient")
    public WebClient chatWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.chat.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    @Bean("engineWebClient")
    public WebClient engineWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.engine.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    @Bean("statisticWebClient")
    public WebClient statisticWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.statistic.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    private WebClient buildClient(WebClient.Builder builder, String baseUrl) {
        return builder.clone().baseUrl(baseUrl).build();
    }
}
