package com.itda.backend.domain.auth.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KakaoUserInfoResponse {

    private Long id;
    private KakaoAccount kakaoAccount;

    @Getter
    @NoArgsConstructor
    public static class KakaoAccount {
        private Profile profile;

        @Getter
        @NoArgsConstructor
        public static class Profile {
            private String nickname;
        }
    }
}
