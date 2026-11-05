package com.itda.backend.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationReasonsTest {

    /** AI 가 보내는 유형(AI/validation/config.py ISSUE_LEVEL, nodes.py)이 모두 "~요" 문장으로 바뀐다. */
    @Test
    void AI_검증_유형을_선생님이_읽을_문장으로_바꾼다() {
        assertThat(ValidationReasons.sentencesOf(List.of(
                "진단명", "개인정보표현", "확정적표현", "다수아동언급", "추측성표현",
                "감정적표현", "위험행동표현", "건강관리기록", "대상불명확", "모델호출실패")))
                .containsExactly(
                        "진단명이나 장애 유형이 단정적으로 적혀 있어요",
                        "전화번호·주민번호 같은 개인정보가 그대로 적혀 있어요",
                        "관찰이 아니라 단정하는 표현이 있어요",
                        "다른 아이의 이름이 함께 적혀 있어요",
                        "관찰되지 않은 내용을 추측한 표현이 있어요",
                        "감정이나 평가가 섞인 표현이 있어요",
                        "위험 행동이 그대로 묘사돼 있어요",
                        "질병·복약·병원 진료 같은 건강 정보가 적혀 있어요",
                        "어느 아이의 기록인지 분명하지 않아요",
                        "자동 검사를 마치지 못했어요. 직접 읽고 확인해 주세요");
    }

    /** AI 가 유형을 새로 만들면 표에 없는 값이 온다 — 빈칸보다 원래 코드가 낫다. */
    @Test
    void 표에_없는_유형은_원래_코드를_그대로_둔다() {
        assertThat(ValidationReasons.sentencesOf(List.of("새유형"))).containsExactly("새유형");
    }

    /**
     * AI 가 판정만 주고 유형을 안 보내거나 깨진 값을 보내도 빈칸으로 두지 않는다. 수정 요청 큐와 Gate 1 이 같은 문장을
     * 쓴다 — 전에는 Gate 1 안내가 "원본 일지 1건 · " 에서 끊겼다.
     */
    @Test
    void 유형이_없으면_원본을_확인하라는_문장_하나를_준다() {
        assertThat(ValidationReasons.sentencesOf(List.of()))
                .containsExactly("검증에서 문제가 발견됐어요. 원본을 확인해 주세요");
    }

    /** 빈 문자열·공백 유형은 문장이 아니라 빈칸이다. 버리고, 남는 게 없으면 원본을 확인하라는 문장이다. */
    @Test
    void 빈_유형은_버리고_남는_게_없으면_원본을_확인하라고_한다() {
        assertThat(ValidationReasons.sentencesOf(List.of("", " ")))
                .containsExactly("검증에서 문제가 발견됐어요. 원본을 확인해 주세요");
        assertThat(ValidationReasons.sentencesOf(List.of("", "추측성표현")))
                .containsExactly("관찰되지 않은 내용을 추측한 표현이 있어요");
    }

    /** 앞뒤 공백이 붙어 와도 같은 유형이다 — 코드가 그대로 화면에 나가지 않게. */
    @Test
    void 앞뒤_공백이_붙은_유형도_문장으로_바꾼다() {
        assertThat(ValidationReasons.sentencesOf(List.of(" 추측성표현", "추측성표현 ")))
                .containsExactly("관찰되지 않은 내용을 추측한 표현이 있어요");
    }

    /** 같은 문장이 두 번 서지 않게 한 번만 남긴다. 순서는 받은 순서 그대로다. */
    @Test
    void 같은_유형이_겹치면_한_번만_남긴다() {
        assertThat(ValidationReasons.sentencesOf(List.of("추측성표현", "다수아동언급", "추측성표현")))
                .containsExactly("관찰되지 않은 내용을 추측한 표현이 있어요", "다른 아이의 이름이 함께 적혀 있어요");
    }
}
