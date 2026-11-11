package com.itda.backend.global.config;

import java.time.Duration;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;

public final class HttpClientRequestFactories {

    private HttpClientRequestFactories() {
    }

    public static ClientHttpRequestFactory create(Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(connectTimeout, readTimeout);
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .withHttpClientCustomizer(client -> client.setRetryStrategy(retryStrategy()))
                .build(settings);
    }

    static HttpRequestRetryStrategy retryStrategy() {
        return new DefaultHttpRequestRetryStrategy() {
            @Override
            public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
                return false;
            }
        };
    }
}
