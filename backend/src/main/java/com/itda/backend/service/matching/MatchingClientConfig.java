package com.itda.backend.service.matching;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingClientConfig {

    /**
     * 타임아웃은 여기서만 정한다. {@link MatchingAgentClient} 안에서 요청 팩토리를 다시 바꾸면
     * 테스트의 MockRestServiceServer 가 덮여서 실제 네트워크로 나간다.
     */
    @Bean
    public RestClient matchingAgentRestClient(RestClient.Builder builder, MatchingProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(properties.connectTimeout(), properties.readTimeout());
        return builder
                .baseUrl(properties.aiBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
