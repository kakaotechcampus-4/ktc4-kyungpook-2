package com.itda.backend.dto.response.consent;

import java.util.List;

import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.dto.response.organization.InstitutionResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * G-02 동의 미리보기. 보호자가 "동의하기"를 누르기 전에 보는 화면(P-02)이 한 번에 그리는 정보다.
 *
 * @param documentUrl     기관 증빙서류. 저장 기능이 없어 지금은 언제나 null 이다 — 화면이 버튼을 숨긴다
 * @param sharedFields    사용 중단. 기관 유형별 설명 문구 — 실제 공유와 맞지 않아 화면이 더 쓰지 않는다. 이전 화면
 *                        호환으로 남기고 새 화면 배포 뒤 지운다
 * @param notSharedFields 사용 중단. {@code sharedFields} 와 같다
 * @param allowedFields   동의할 때 G-41 에 그대로 돌려보낼 항목 코드. 저장되는 것은 이 값이다
 */
public record ConsentPreviewResponse(
        PreviewChild child,
        InstitutionResponse institution,
        String documentUrl,
        @Schema(deprecated = true, description = "사용 중단 — 실제 공유와 맞지 않는 설명 문구. 화면은 쓰지 않는다. 이전 화면 호환으로 남김")
        List<String> sharedFields,
        @Schema(deprecated = true, description = "사용 중단 — sharedFields 와 같다")
        List<String> notSharedFields,
        List<ConsentField> allowedFields) {

    /** {@code id} 는 동의(G-41)·연결 해제(G-05) 호출에 쓴다. */
    public record PreviewChild(String id, String name, String birthDate) {
    }
}
