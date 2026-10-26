package com.itda.backend.dto.request.guardian;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/v1/guardians/me/insights/{insightId}/share 의 요청 (#223).
 *
 * <p><b>고른 기관 전부를 보낸다.</b> 켜고 끄기를 따로 받지 않고 이 목록을 최종 상태로 맞춘다 —
 * 화면이 체크박스라 그 모양이 그대로 오고, 같은 요청을 두 번 보내도 결과가 같다.
 *
 * <p>빈 배열은 "전부 끈다" 는 뜻이라 받는다. Gate 2 와 달리 <b>되돌릴 수 있다</b> — 보호자가
 * 자기 아이 정보의 주인이고, 열람 방식을 고른 이유가 "철회하면 그 뒤로 안 보인다" 였다 (§9.4).
 */
public record GuardianShareRequest(
        @Schema(description = "이 인사이트를 볼 수 있게 할 기관 전부. 여기 없는 기관은 꺼진다",
                example = "[1, 3]")
        @NotNull
        List<Long> institutionIds
) {
}
