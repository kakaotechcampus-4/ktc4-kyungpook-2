package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.dto.response.SummaryAgentResponse;

/** 요약 저장 직전 BE 근거 재검사 (멘토 리뷰 A4, #129). */
class SummaryEvidenceCheckerTest {

    private static final SummaryGroup GROUP = new SummaryGroup(8L, LocalDate.of(2026, 10, 8), 3L);
    private static final Map<Long, String> SOURCES = Map.of(
            1041L, "블록 놀이에서 친구에게 양보함",
            1042L, "오늘 😀 블록을 쌓았다");
    /** AI 에 보낸 일지. 1043 은 보낸 뒤 요약하는 사이 삭제돼 SOURCES 에 없다. */
    private static final Set<Long> SENT = Set.of(1041L, 1042L, 1043L);

    private final SummaryEvidenceChecker checker = new SummaryEvidenceChecker();

    private SummaryAgentResponse response(String claims) {
        return response(8, "2026-10-08", 3, claims, "[1041, 1042]", "[]");
    }

    private SummaryAgentResponse response(long childId, String entryDate, long institutionId, String claims,
            String covered, String uncovered) {
        String json = """
                {"child_id": %d, "entry_date": "%s", "institution_id": %d, "content": "본문",
                 "claims": %s, "covered_entry_ids": %s, "uncovered_entry_ids": %s, "llm_called": true}
                """.formatted(childId, entryDate, institutionId, claims, covered, uncovered);
        try {
            return new ObjectMapper().readValue(json, SummaryAgentResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String evidence(long id, String quote, int start, int end) {
        return """
                {"journal_entry_id": %d, "quote": "%s", "span": {"start": %d, "end": %d}}
                """.formatted(id, quote, start, end);
    }

    private static String claim(String... evidences) {
        return "{\"text\": \"문장\", \"evidence\": [" + String.join(",", evidences) + "]}";
    }

    @Test
    void 한_문장이_일지_두_개를_근거로_들어도_통과한다() {
        String claims = "[" + claim(evidence(1041, "친구에게 양보함", 8, 16), evidence(1042, "블록을 쌓았다", 5, 12)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(claims))).isEmpty();
    }

    @Test
    void 이모지가_앞에_있으면_코드포인트_기준_span만_통과한다() {
        // "오늘 😀 블록을 쌓았다" — 😀 는 코드포인트 1개, UTF-16 char 2개다.
        String codePointSpan = "[" + claim(evidence(1042, "블록을 쌓았다", 5, 12)) + "]";
        String utf16Span = "[" + claim(evidence(1042, "블록을 쌓았다", 6, 13)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(codePointSpan))).isEmpty();
        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(utf16Span))).isPresent();
    }

    @Test
    void 이번_묶음_재료에_없는_일지를_근거로_들면_걸린다() {
        // 요약하는 사이 삭제된 일지도 재료에서 빠지므로 여기 걸린다.
        String claims = "[" + claim(evidence(9999, "친구에게 양보함", 8, 16)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(claims))).get().asString()
                .contains("claim[0]").contains("9999");
    }

    @Test
    void 인용이_원문_구간과_다르면_걸리고_사유에_원문을_넣지_않는다() {
        String claims = "[" + claim(evidence(1041, "친구에게 양보했다", 8, 16)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(claims))).get().asString()
                .doesNotContain("양보");
    }

    @Test
    void span이_원문_범위_밖이거나_거꾸로면_걸린다() {
        for (String ev : new String[] {
                evidence(1041, "x", -1, 3),
                evidence(1041, "x", 7, 99),
                evidence(1041, "x", 5, 5),
                evidence(1041, "x", 9, 7)}) {
            assertThat(checker.findProblem(GROUP, SENT, SOURCES, response("[" + claim(ev) + "]"))).isPresent();
        }
    }

    @Test
    void span이_없으면_걸린다() {
        String claims = "[{\"text\": \"문장\", \"evidence\": "
                + "[{\"journal_entry_id\": 1041, \"quote\": \"친구에게 양보함\"}]}]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(claims))).isPresent();
    }

    @Test
    void 근거가_하나도_없는_문장이_있으면_걸린다() {
        String claims = "[" + claim(evidence(1041, "친구에게 양보함", 8, 16)) + ", " + claim() + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(claims))).get().asString().contains("claim[1]");
    }

    @Test
    void 문장이_하나도_없으면_걸린다() {
        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response("[]"))).isPresent();
    }

    @Test
    void 응답이_다른_묶음의_것이면_걸린다() {
        String claims = "[" + claim(evidence(1041, "친구에게 양보함", 8, 16)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(9, "2026-10-08", 3, claims, "[1041]", "[]")))
                .isPresent();
        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(8, "2026-10-09", 3, claims, "[1041]", "[]")))
                .isPresent();
        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(8, "2026-10-08", 4, claims, "[1041]", "[]")))
                .isPresent();
    }

    @Test
    void 반영_목록에_재료에_없는_일지가_있으면_걸린다() {
        String claims = "[" + claim(evidence(1041, "친구에게 양보함", 8, 16)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(8, "2026-10-08", 3, claims, "[1041, 9999]", "[]")))
                .isPresent();
        assertThat(checker.findProblem(GROUP, SENT, SOURCES, response(8, "2026-10-08", 3, claims, "[1041]", "[9999]")))
                .isPresent();
    }

    @Test
    void 요약하는_사이_삭제된_일지가_반영_안_됨_목록에_있는_것은_괜찮다() {
        // AI 는 보낸 일지를 모두 covered 나 uncovered 에 넣는다. 근거로 들지 않았으면 삭제됐어도 문제가 아니다.
        String claims = "[" + claim(evidence(1041, "친구에게 양보함", 8, 16)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES,
                response(8, "2026-10-08", 3, claims, "[1041]", "[1042, 1043]"))).isEmpty();
    }

    @Test
    void 요약하는_사이_삭제된_일지를_근거로_들면_걸린다() {
        String claims = "[" + claim(evidence(1043, "점심을 먹었다", 0, 7)) + "]";

        assertThat(checker.findProblem(GROUP, SENT, SOURCES,
                response(8, "2026-10-08", 3, claims, "[1043]", "[1041, 1042]"))).isPresent();
    }
}
