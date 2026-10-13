package com.itda.backend.service.insight;

import java.util.Map;

import com.itda.backend.domain.OrganizationType;

/**
 * 기관 종류를 인사이트 에이전트가 쓰는 한국어로 바꾼다.
 *
 * <p>이 문자열은 <b>AI 계약의 어휘</b>다 — {@code AI/insight/config.py} 의
 * {@code INSTITUTION_TYPES = ("학교", "센터", "활동지원사")} 와 같아야 한다. 값이 프롬프트에 그대로 들어가고
 * ({@code prompts.py}), 기관 간 비교도 이 문자열끼리 맞춰 본다 ({@code nodes.py}). 영문 enum 이름을 보내면
 * 모델이 "학교에서는 …" 같은 문장을 쓰지 못한다.
 *
 * <p><b>{@link OrganizationType} 에 라벨을 붙이지 않는 이유</b> — 이건 BE 도메인 어휘가 아니라 저쪽 계약의
 * 어휘다. enum 에 넣어 두면 AI 가 표현을 바꿀 때 도메인 클래스를 고치게 되고, 다른 에이전트가 다른 표기를
 * 요구할 때 갈 곳이 없어진다. 기관 <b>이름</b>은 보내지 않는다 — 비교는 종류로만 한다 (CRITERIA §5).
 */
final class InstitutionTypeNames {

    private static final Map<OrganizationType, String> NAMES = Map.of(
            OrganizationType.SCHOOL, "학교",
            OrganizationType.CENTER, "센터",
            OrganizationType.ACTIVITY_SUPPORT, "활동지원사");

    private InstitutionTypeNames() {
    }

    /**
     * 기관을 못 찾았을 때는 그 요약만 건너뛰는데 여기서는 던진다 — 일부러 다르다. 종류를 모르는 기관은
     * 빼면 그만이지만, <b>잘못된 종류</b>를 보내면 모델이 "학교에서는 …" 같은 문장을 틀린 기관에 붙인다.
     * 그건 조용히 틀린 글이 다른 기관으로 나가는 쪽이라 멈추는 게 낫다.
     *
     * @throws IllegalStateException 새 기관 종류가 생겼는데 여기 매핑을 안 넣은 경우. 조용히 {@code null} 을
     *                               보내면 AI 가 422 로 거절하고, 422 는 재시도 없이 바로 실패한다
     */
    static String of(OrganizationType type) {
        String name = NAMES.get(type);
        if (name == null) {
            throw new IllegalStateException(
                    "인사이트가 쓸 기관 종류 이름이 없습니다. AI/insight/config.py 와 맞춰 주세요: " + type);
        }
        return name;
    }
}
