package com.itda.backend.domain.auth.controller;

import com.itda.backend.domain.auth.dto.response.LoginResponse;
import com.itda.backend.domain.auth.service.KakaoAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final KakaoAuthService kakaoAuthService;

    public AuthController(KakaoAuthService kakaoAuthService) {
        this.kakaoAuthService = kakaoAuthService;
    }

    @PostMapping("/kakao")
    public LoginResponse kakaoLogin(@RequestParam String code) {
        return kakaoAuthService.login(code);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<String> handleKakaoCommunicationError(RestClientException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("카카오 인증에 실패했습니다.");
    }
}
