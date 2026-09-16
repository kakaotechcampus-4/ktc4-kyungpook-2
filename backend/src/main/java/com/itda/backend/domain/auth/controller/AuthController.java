package com.itda.backend.domain.auth.controller;

import com.itda.backend.domain.auth.dto.response.LoginResponse;
import com.itda.backend.domain.auth.service.KakaoAuthService;
import com.itda.backend.global.response.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final KakaoAuthService kakaoAuthService;

    public AuthController(KakaoAuthService kakaoAuthService) {
        this.kakaoAuthService = kakaoAuthService;
    }

    @PostMapping("/kakao")
    public ApiResponse<LoginResponse> kakaoLogin(@RequestParam @NotBlank String code) {
        return ApiResponse.success(kakaoAuthService.login(code));
    }
}
