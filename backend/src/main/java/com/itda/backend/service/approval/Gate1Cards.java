package com.itda.backend.service.approval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itda.backend.domain.approval.Gate1CardAction;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.summary.Gate1DecisionRequest.Card;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;

/**
 * 교사가 Gate 1 에서 고른 문장 카드로 승인할 claims 와 본문을 만든다 (#221).
 *
 * <p>인사이트는 본문이 아니라 {@code claims} 를 읽는다. 교사가 지운 문장이 claims 에 남으면 다른 기관으로 나가므로,
 * 고쳐 승인하면 claims 를 카드 결과로 다시 쓴다. 문장마다 누가 썼는지 {@code source} 를 붙인다 — 교사 문장은 검증을
 * 거치지 않아 Gate 2 가 이 값으로 경고를 띄운다.
 *
 * <pre>
 * KEEP    원래 claim 그대로 + "source": "AI"
 * DELETE  넣지 않는다
 * EDIT    text 만 바꾸고 evidence 는 그대로 + "source": "EDITED"
 * ADD     {"text", "evidence": [{"journal_entry_id": null, "quote": text}], "source": "TEACHER"}
 * </pre>
 *
 * 최종 순서는 카드 순서다. 고친 글이 원문과 같거나 카드가 모두 원래 순서대로 KEEP 이면 고치지 않은 것이다.
 */
final class Gate1Cards {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param claims    승인할 claims JSON. 고치지 않았으면 원래 값 그대로다
     * @param content   child_context 에 올릴 본문. 고치지 않았으면 AI 원문, 고쳤으면 남은 문장을 이은 글이다
     * @param corrected 교사가 하나라도 바꿨는지
     */
    record Applied(String claims, String content, boolean corrected) {
    }

    private Gate1Cards() {
    }

    /** 원래 문장을 모두 한 번씩 담지 않았거나 남는 문장이 없으면 400 INVALID_REQUEST 다. */
    static Applied apply(SummaryResult summary, List<Card> cards) {
        ArrayNode original = readClaims(summary.getClaims());
        requireEachOriginalOnce(summary, original, cards);

        ArrayNode result = MAPPER.createArrayNode();
        List<String> texts = new ArrayList<>();
        boolean corrected = false;
        for (int position = 0; position < cards.size(); position++) {
            Card card = cards.get(position);
            Gate1CardAction action = effectiveAction(card, original);
            // 모두 KEEP 이면 카드 수가 원래 문장 수와 같으니, 순서가 그대로인지는 자리와 번호가 같은지로 본다.
            if (action != Gate1CardAction.KEEP || !card.index().equals(position)) {
                corrected = true;
            }
            ObjectNode claim = switch (action) {
                case KEEP -> withSource(original.get(card.index()).deepCopy(), "AI");
                case EDIT -> withSource(((ObjectNode) original.get(card.index()).deepCopy())
                        .put("text", normalize(card.text())), "EDITED");
                case ADD -> teacherClaim(normalize(card.text()));
                case DELETE -> null;
            };
            if (claim != null) {
                result.add(claim);
                String text = claim.path("text").asText("");
                if (!text.isBlank()) {
                    texts.add(text.strip());
                }
            }
        }
        if (result.isEmpty()) {
            throw new SummaryException(SummaryErrorCode.INVALID_REQUEST,
                    "summaryId=" + summary.getId() + " no card left");
        }
        if (!corrected) {
            return new Applied(summary.getClaims(), summary.getContent(), false);
        }
        // AI 도 claims 의 text 를 " " 로 이어 본문을 만든다 (AI/summary/nodes.py assemble).
        return new Applied(result.toString(), String.join(" ", texts), true);
    }

    /**
     * 입력창을 거치며 생기는 차이(CRLF, 앞뒤 공백·줄바꿈)는 고친 것으로 보지 않는다. 고친 것으로 보면 근거가 같은데도
     * 교사가 고친 문장으로 표시된다. 글 안의 글자는 한 글자라도 다르면 고친 것이다.
     */
    static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /** 고친 글이 원문과 같으면 그대로 둔 것이다. */
    private static Gate1CardAction effectiveAction(Card card, ArrayNode original) {
        Gate1CardAction action = card.cardAction();
        if (action == Gate1CardAction.EDIT
                && normalize(card.text()).equals(normalize(original.get(card.index()).path("text").asText()))) {
            return Gate1CardAction.KEEP;
        }
        return action;
    }

    /** ADD 가 아닌 카드는 원래 문장 번호를 0부터 끝까지 한 번씩 가리켜야 한다. 빠지면 교사가 못 본 문장이 승인된다. */
    private static void requireEachOriginalOnce(SummaryResult summary, ArrayNode original, List<Card> cards) {
        Set<Integer> seen = new HashSet<>();
        for (Card card : cards) {
            Integer index = card.index();
            if (card.cardAction() == Gate1CardAction.ADD) {
                continue;
            }
            if (index == null || index < 0 || index >= original.size() || !seen.add(index)) {
                throw new SummaryException(SummaryErrorCode.INVALID_REQUEST,
                        "summaryId=" + summary.getId() + " invalid card index=" + index);
            }
        }
        if (seen.size() != original.size()) {
            throw new SummaryException(SummaryErrorCode.INVALID_REQUEST,
                    "summaryId=" + summary.getId() + " cards=" + seen.size() + " claims=" + original.size());
        }
    }

    private static ObjectNode withSource(JsonNode claim, String source) {
        return ((ObjectNode) claim).put("source", source);
    }

    /** 교사가 쓴 문장은 일지가 없어 그 문장 자체를 근거로 둔다. 키를 빼지 않고 null 로 둔다 — 인사이트 계약 모양이다. */
    private static ObjectNode teacherClaim(String text) {
        ObjectNode claim = MAPPER.createObjectNode().put("text", text);
        claim.putArray("evidence").addObject().putNull("journal_entry_id").put("quote", text);
        return claim.put("source", "TEACHER");
    }

    /**
     * 승인 전 claims 는 AI 가 보낸 문장 객체 배열이다. 못 읽거나 문장이 아닌 값이 섞였으면 문장이 없는 것으로 본다 —
     * 카드를 보낸 승인은 번호가 맞지 않아 400 이 되고, 그런 요약은 카드 없이 승인하거나 제거한다. Gate 1 목록의 카드도
     * 이것으로 읽어, 목록에 보인 카드와 결정 때 검사하는 문장이 같게 한다.
     */
    static ArrayNode readClaims(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node.isArray()) {
                for (JsonNode claim : node) {
                    if (!claim.isObject()) {
                        return MAPPER.createArrayNode();
                    }
                }
                return (ArrayNode) node;
            }
        } catch (JsonProcessingException e) {
            // 아래에서 빈 배열로 돌린다.
        }
        return MAPPER.createArrayNode();
    }
}
