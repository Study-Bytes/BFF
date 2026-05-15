
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

    @Bean("courseWebClient")
    public WebClient courseWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.course.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    @Bean("learningWebClient")
    public WebClient learningWebClient(
            @Qualifier("webClientBuilder") WebClient.Builder builder,
            @Value("${svc.learning.base-url}") String baseUrl
    ) {
        return buildClient(builder, baseUrl);
    }

    private WebClient buildClient(WebClient.Builder builder, String baseUrl) {
        return builder.clone().baseUrl(baseUrl).build();
    }
}
