package com.itda.backend.service.validation;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * AI 검증이 보낸 이슈 유형을 선생님이 읽을 문장으로 바꾼다. 수정 요청 큐(O-24)와 Gate 1 확인 표시(O-30)가 같은 표를 쓴다
 * — 같은 문제를 화면마다 다른 말로 부르지 않게 (#351).
 *
 * <p>유형은 AI/validation/config.py {@code ISSUE_LEVEL} 과 nodes.py 가 붙이는 {@code 대상불명확}·{@code 모델호출실패}
 * 다. AI 가 유형을 새로 만들면 표에 없는 값이 올 수 있어서, 없으면 원래 코드를 그대로 둔다 — 빈칸보다 낫다.
 */
public final class ValidationReasons {

    private static final Map<String, String> SENTENCES = Map.ofEntries(
            Map.entry("진단명", "진단명이나 장애 유형이 단정적으로 적혀 있어요"),
            Map.entry("개인정보표현", "전화번호·주민번호 같은 개인정보가 그대로 적혀 있어요"),
            Map.entry("확정적표현", "관찰이 아니라 단정하는 표현이 있어요"),
            Map.entry("다수아동언급", "다른 아이의 이름이 함께 적혀 있어요"),
            Map.entry("추측성표현", "관찰되지 않은 내용을 추측한 표현이 있어요"),
            Map.entry("감정적표현", "감정이나 평가가 섞인 표현이 있어요"),
            Map.entry("위험행동표현", "위험 행동이 그대로 묘사돼 있어요"),
            Map.entry("건강관리기록", "질병·복약·병원 진료 같은 건강 정보가 적혀 있어요"),
            Map.entry("대상불명확", "어느 아이의 기록인지 분명하지 않아요"),
            Map.entry("모델호출실패", "자동 검사를 마치지 못했어요. 직접 읽고 확인해 주세요"));

    private ValidationReasons() {
    }

    /** 받은 순서대로 바꾸고, 같은 문장은 한 번만 남긴다. */
    public static List<String> sentencesOf(Collection<String> issueTypes) {
        return issueTypes.stream()
                .map(type -> SENTENCES.getOrDefault(type, type))
                .distinct()
                .toList();
    }
}
