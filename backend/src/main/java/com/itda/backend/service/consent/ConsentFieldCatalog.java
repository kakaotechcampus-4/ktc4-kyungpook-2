package com.itda.backend.service.consent;

import java.util.List;
import java.util.Map;

import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.organization.OrganizationType;

/**
 * 동의 화면(P-02)에 보여 줄 공유 범위 설명. FE 가 기관 유형별 상수로 들고 있던 표를 그대로 옮겼다 (api-spec G-02).
 *
 * <p>문구는 설명일 뿐 저장하지 않는다. 실제로 저장·적용되는 것은 {@link #ALLOWED_FIELDS} 의 항목 코드다.
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
