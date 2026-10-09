package com.itda.backend.service.summary;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.dto.response.SummaryAgentResponse;

/**
 * 요약을 저장하기 직전에 AI 가 단 근거를 BE 가 다시 대조한다 (멘토 리뷰 A4, #129).
 *
 * <p>AI(/summary)도 인용을 원문에서 찾아 span 을 채우고 못 찾은 근거는 버린다. 그래도 다시 보는 이유는 AI 쪽 버그나
 * 계약 어긋남이 그대로 저장돼 교사가 근거 없는 문장을 승인하는 것을 막기 위해서다.
 *
 * <p>응답을 고치지 않는다. 문장 하나를 빼면 content·covered 를 BE 가 다시 만들어야 해서 AI 로직이 중복된다.
 * 문제가 하나라도 있으면 그 사유를 돌려주고, 저장하는 쪽이 요약 전체를 실패로 처리한다.
 *
 * <p>사유에는 위치(문장·근거 순번, 일지 id)와 이유만 담는다. 아이 기록이라 본문이나 인용 원문은 넣지 않는다.
 */
@Component
public class SummaryEvidenceChecker {

    /**
     * @param sources 이번 요약의 재료 일지 id → 원문. AI 에 실제로 보낸 원문이고, 그새 삭제된 일지는 뺀 것이다 —
     *                삭제된 일지를 가리키는 근거도 여기서 걸린다
     * @return 문제가 있으면 그 사유, 없으면 빈 값
     */
    public Optional<String> findProblem(SummaryGroup group, Map<Long, String> sources,
            SummaryAgentResponse response) {
        if (!group.childId().equals(response.childId())
                || !group.entryDate().toString().equals(response.entryDate())
                || !group.institutionId().equals(response.institutionId())) {
            return Optional.of("response is for another group childId=" + response.childId()
                    + " entryDate=" + response.entryDate() + " institutionId=" + response.institutionId());
        }
        Optional<String> listed = checkEntryIds("covered_entry_ids", response.coveredEntryIds(), sources)
                .or(() -> checkEntryIds("uncovered_entry_ids", response.uncoveredEntryIds(), sources));
        if (listed.isPresent()) {
            return listed;
        }

        JsonNode claims = response.claims();
        if (claims == null || !claims.isArray() || claims.isEmpty()) {
            return Optional.of("no claims");
        }
        for (int i = 0; i < claims.size(); i++) {
            JsonNode evidence = claims.get(i).get("evidence");
            if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
                return Optional.of("claim[" + i + "]: no evidence");
            }
            for (int j = 0; j < evidence.size(); j++) {
                Optional<String> problem = checkEvidence(evidence.get(j), sources);
                if (problem.isPresent()) {
                    return Optional.of("claim[" + i + "].evidence[" + j + "]: " + problem.get());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> checkEntryIds(String field, JsonNode ids, Map<Long, String> sources) {
        if (ids == null || ids.isNull()) {
            return Optional.empty();
        }
        for (JsonNode id : ids) {
            if (!sources.containsKey(id.asLong())) {
                return Optional.of(field + " has entry not in group journalEntryId=" + id.asLong());
            }
        }
        return Optional.empty();
    }

    private Optional<String> checkEvidence(JsonNode evidence, Map<Long, String> sources) {
        long journalEntryId = evidence.path("journal_entry_id").asLong(-1);
        String content = sources.get(journalEntryId);
        if (content == null) {
            return Optional.of("journal entry not in group journalEntryId=" + journalEntryId);
        }
        JsonNode span = evidence.get("span");
        if (span == null || !span.path("start").isInt() || !span.path("end").isInt()) {
            return Optional.of("no span journalEntryId=" + journalEntryId);
        }
        // span 은 Python 문자열 인덱스(유니코드 코드포인트) 기준이고 end 는 포함하지 않는다.
        int start = span.get("start").asInt();
        int end = span.get("end").asInt();
        int length = content.codePointCount(0, content.length());
        if (start < 0 || start >= end || end > length) {
            return Optional.of("span out of range journalEntryId=" + journalEntryId
                    + " start=" + start + " end=" + end + " length=" + length);
        }
        // String.substring 은 UTF-16 char 기준이라 이모지 같은 문자가 앞에 있으면 어긋난다. 코드포인트로 바꿔서 자른다.
        String sliced = content.substring(content.offsetByCodePoints(0, start), content.offsetByCodePoints(0, end));
        if (!sliced.equals(evidence.path("quote").asText(null))) {
            return Optional.of("quote does not match span journalEntryId=" + journalEntryId
                    + " start=" + start + " end=" + end);
        }
        return Optional.empty();
    }
}
