package com.itda.backend.service.consent;

import java.util.List;
import java.util.Map;

import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.organization.OrganizationType;

/**
 * 동의할 때 받을 공유 항목 코드와, 동의 미리보기(G-02)가 내려주던 기관 유형별 설명 문구.
 *
 * <p>실제로 저장·적용되는 것은 {@link #ALLOWED_FIELDS} 의 항목 코드다. 문구표({@code LABELS})는 <b>사용 중단</b>이다 —
 * "알레르기 정보 공유" 처럼 거르는 코드가 없는 약속이라 실제 공유와 맞지 않는다. 화면은 고정 문구를 쓰고, 이 표는
 * 이전 화면 호환으로만 응답에 남긴다. 새 화면 배포 뒤 지운다. 다른 곳에서 쓰지 않는다.
 */
final class ConsentFieldCatalog {

    /** 지금은 모든 기관 유형에 같은 항목을 연다. */
    static final List<ConsentField> ALLOWED_FIELDS = List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT);

    private static final Map<OrganizationType, Labels> LABELS = Map.of(
            OrganizationType.SCHOOL, new Labels(
                    List.of("출결 시간", "활동 요약", "알레르기 정보"),
                    List.of("사진과 영상", "의료 기록")),
            OrganizationType.CENTER, new Labels(
                    List.of("관찰 요약", "변화 추이", "집에서 해본 방법"),
                    List.of("복지카드 정보")),
            OrganizationType.ACTIVITY_SUPPORT, new Labels(
                    List.of("하원 시간", "이동 경로", "필요한 지원 방법"),
                    List.of("학습 기록")));

    private ConsentFieldCatalog() {
    }

    static Labels labelsOf(OrganizationType type) {
        return LABELS.get(type);
    }

    record Labels(List<String> shared, List<String> notShared) {
    }
}
