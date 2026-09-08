package com.itda.backend.domain.auth.service;

import com.itda.backend.domain.auth.dto.response.KakaoTokenResponse;
import com.itda.backend.domain.auth.dto.response.KakaoUserInfoResponse;
import com.itda.backend.domain.auth.dto.response.LoginResponse;
import com.itda.backend.global.jwt.JwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class KakaoAuthService {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient = RestClient.create();
    private final JwtProvider jwtProvider;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public KakaoAuthService(
            JwtProvider jwtProvider,
            @Value("${kakao.client-id}") String clientId,
            @Value("${kakao.client-secret}") String clientSecret,
            @Value("${kakao.redirect-uri}") String redirectUri
    ) {
        this.jwtProvider = jwtProvider;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    public LoginResponse login(String code) {
        String kakaoAccessToken = exchangeToken(code);
        KakaoUserInfoResponse userInfo = getUserInfo(kakaoAccessToken);

        String jwt = jwtProvider.createToken(String.valueOf(userInfo.getId()));
        String nickname = extractNickname(userInfo);

        return new LoginResponse(jwt, userInfo.getId(), nickname);
    }

    private String exchangeToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);

        KakaoTokenResponse response = restClient.post()
                .uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class);

        return response.getAccessToken();
    }

    private KakaoUserInfoResponse getUserInfo(String kakaoAccessToken) {
        return restClient.get()
                .uri(USER_INFO_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                .retrieve()
                .body(KakaoUserInfoResponse.class);
    }

    private String extractNickname(KakaoUserInfoResponse userInfo) {
        if (userInfo.getKakaoAccount() == null || userInfo.getKakaoAccount().getProfile() == null) {
            return null;
        }
        return userInfo.getKakaoAccount().getProfile().getNickname();
    }
}
