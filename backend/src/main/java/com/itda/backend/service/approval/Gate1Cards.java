package com.itda.backend.service.approval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itda.backend.domain.approval.Gate1CardAction;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.summary.Gate1DecisionRequest.Card;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.global.json.JsonColumns;

final class Gate1Cards {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Applied(String claims, String content, boolean corrected) {
    }

    private Gate1Cards() {
    }

    static Applied apply(SummaryResult summary, List<Card> cards) {
        if (cards == null) {
            return unchanged(summary);
        }
        ArrayNode original = readClaims(summary);
        if (original.isEmpty()) {
            throw new SummaryException(SummaryErrorCode.INVALID_REQUEST,
                    "summaryId=" + summary.getId() + " no readable claims");
        }
        requireEachOriginalOnce(summary, original, cards);

        ArrayNode result = MAPPER.createArrayNode();
        List<String> texts = new ArrayList<>();
        boolean corrected = false;
        for (int position = 0; position < cards.size(); position++) {
            Card card = cards.get(position);
            Gate1CardAction action = effectiveAction(card, original);
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
            return unchanged(summary);
        }
        return new Applied(result.toString(), String.join(" ", texts), true);
    }

    private static Applied unchanged(SummaryResult summary) {
        return new Applied(summary.getClaims(), summary.getContent(), false);
    }

    static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static Gate1CardAction effectiveAction(Card card, ArrayNode original) {
        Gate1CardAction action = card.cardAction();
        if (action == Gate1CardAction.EDIT
                && normalize(card.text()).equals(normalize(original.get(card.index()).path("text").asText()))) {
            return Gate1CardAction.KEEP;
        }
        return action;
    }

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

    private static ObjectNode teacherClaim(String text) {
        ObjectNode claim = MAPPER.createObjectNode().put("text", text);
        claim.putArray("evidence").addObject().putNull("journal_entry_id").put("quote", text);
        return claim.put("source", "TEACHER");
    }

    static ArrayNode readClaims(SummaryResult summary) {
        JsonNode node = JsonColumns.readArray(summary.getClaims(), "summary_result.claims", summary.getId());
        for (JsonNode claim : node) {
            if (!claim.isObject()) {
                return MAPPER.createArrayNode();
            }
        }
        return (ArrayNode) node;
    }
}
