package com.itda.backend.service.summary;

import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;

final class NothingNotable {

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

    static SummaryAgentReply reply(SummaryAgentRequest request) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ArrayNode uncovered = json.arrayNode();
        request.sources().forEach(source -> uncovered.add(source.journalEntryId()));
        SummaryAgentResponse response = new SummaryAgentResponse(request.childId(), request.entryDate(),
                request.institutionId(), "", json.arrayNode(), json.arrayNode(), uncovered, false, json.arrayNode(),
                true, false);
        String raw = json.objectNode().put("no_content", true).put("decided_by", "backend_rule").toString();
        return new SummaryAgentReply(response, raw);
    }
}
