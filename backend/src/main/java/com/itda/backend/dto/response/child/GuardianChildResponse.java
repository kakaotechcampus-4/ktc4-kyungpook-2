package com.itda.backend.dto.response.child;

import java.util.List;

import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * api-spec §3 의 {@code Child} 공통 모델 중 보호자 화면(G-10·G-11)이 받는 부분.
 *
 * <p>{@code school}(학교·학년 문구)·{@code care}(돌봄 정보)는 저장하는 곳이 없어 필드째 내려주지 않는다. 프론트
 * 타입에서도 둘 다 선택 필드라 생략이 계약에 맞는다 — {@code null} 은 프론트 타입이 허용하지 않는다. 돌봄 정보
 * 기능(G-13)이 생기면 그때 {@code care} 를 더한다.
 *
 * @param status       아이 전체 요약 상태 (어느 기관이든 동의가 있으면 active)
 * @param institutions 아이가 지금 명부에 올라 있는 기관. 기관 id 순
 */
public record GuardianChildResponse(
        String id,
        String name,
        String birthDate,
        ChildStatus status,
        List<ChildInstitution> institutions) {

    /** @param consent 아이 기준 동의 상태. 어느 보호자의 동의든 살아 있으면 granted 다 */
    public record ChildInstitution(InstitutionResponse institution, ConsentState consent) {
    }
}
