package com.itda.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityConfigTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void unauthenticatedRequestToProtectedPathReturnsJsonUnauthorized() {
        RequestEntity<Void> request = RequestEntity
                .get(URI.create("http://localhost:" + port + "/api/v1/protected-test-path"))
                .build();

        ResponseEntity<String> response = restTemplate.exchange(request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"result\":\"FAIL\"");
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void allowedOriginReceivesCorsHeaderOnPreflight() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:3000");
        headers.setAccessControlRequestMethod(HttpMethod.POST);

        RequestEntity<Void> request = new RequestEntity<>(
                headers,
                HttpMethod.OPTIONS,
                URI.create("http://localhost:" + port + "/api/v1/auth/logout")
        );

        ResponseEntity<String> response = restTemplate.exchange(request, String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:3000");
    }
}
