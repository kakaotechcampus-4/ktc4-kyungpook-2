package com.itda.backend.service.summary;

import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;

/**
 * "특이사항 없음" 처럼 쓸 관찰 사실이 없다고만 적은 묶음은 AI 를 부르지 않고 요약할 내용 없음(no_content)으로 둔다 (#448).
 *
 * <p>요약 AI 는 이런 일지를 문장으로 옮겼다가 근거 검사에서 버려 503 {@code no_grounded_claims} 를 준다. 그러면 묶음이
 * 요약 실패로 가서 Gate 1 이 아니라 요약 실패 칸에 뜬다(기술멘토링 11/9 시연 피드백). 모델이 처음부터 빈 문장을 내면 이미
 * no_content 로 Gate 1 에 뜨므로(#244), 같은 결과를 규칙으로 먼저 낸다.
 *
 * <p>묶음의 일지가 <b>모두</b> 이 표현일 때만 맞다. 하나라도 다른 내용이 있으면 AI 에 보낸다 — 근거를 못 찾은 요약을
 * 통째로 "특이사항 없음" 으로 바꾸면 실제 내용이 있는 기록이 조용히 사라진다.
 */
final class NothingNotable {

    // 공백 · 문장부호를 지운 뒤 맞춘다. "오늘은 특이사항 없었습니다." · "특이점: 없음" · "이상 무" 같은 것.
    // ponytail: 표현 목록은 보수적이다. 아이 이름이 붙은 문장 등은 AI 로 간다 — 놓친 표현이 보이면 여기 더한다.
    private static final Pattern NOTHING = Pattern.compile(
            "^(오늘(은|도)?)?(특이사항|특이점|특별한사항|특이한사항|특이한점|이상)(은|이)?"
                    + "(없음|없습니다|없었음|없었습니다|없어요|없었어요|무)$");

    private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}·…。、]");

    private NothingNotable() {
    }

    static boolean matches(SummaryAgentRequest request) {
        return !request.sources().isEmpty() && request.sources().stream()
                .allMatch(source -> source.content() != null
                        && NOTHING.matcher(NOISE.matcher(source.content()).replaceAll("")).matches());
    }

    /** AI 가 no_content 로 답한 것과 같은 모양이다 — 본문 "", claims [], 묶음 일지 전부 uncovered. */
    static SummaryAgentReply reply(SummaryAgentRequest request) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ArrayNode uncovered = json.arrayNode();
        request.sources().forEach(source -> uncovered.add(source.journalEntryId()));
        SummaryAgentResponse response = new SummaryAgentResponse(request.childId(), request.entryDate(),
                request.institutionId(), "", json.arrayNode(), json.arrayNode(), uncovered, false, json.arrayNode(),
                true, false);
        // 요약 행의 rawResponse 에 남는다. AI 응답이 아니라 서버 규칙으로 정한 것임을 알 수 있게.
        String raw = json.objectNode().put("no_content", true).put("decided_by", "backend_rule").toString();
        return new SummaryAgentReply(response, raw);
    }
}
