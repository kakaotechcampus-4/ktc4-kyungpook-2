package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;

class NothingNotableTest {

    private static SummaryAgentRequest request(String... contents) {
        List<SummaryAgentRequest.Source> sources = Arrays.stream(contents)
                .map(c -> new SummaryAgentRequest.Source((long) c.length(), c, "2026-11-09"))
                .toList();
        return new SummaryAgentRequest(8L, "하늘", "2026-11-09", 3L, "햇살센터", sources, List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"특이사항 없음", "특이사항 없음.", "특이사항: 없음", "오늘은 특이사항 없었습니다.", "특이점 없음",
            "이상 무", "이상 없습니다", "특이 사항 없어요", "  특이사항없음  "})
    void 특이사항_없음_표현이면_맞다(String content) {
        assertThat(NothingNotable.matches(request(content))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"특이사항 없음. 점심은 다 먹음", "하늘이 특이사항 없음", "없음", "특이사항 있음",
            "급식실에서 소리를 지름", ""})
    void 다른_내용이_있으면_맞지_않는다(String content) {
        assertThat(NothingNotable.matches(request(content))).isFalse();
    }

    @Test
    void 묶음에_다른_내용의_일지가_하나라도_있으면_맞지_않는다() {
        assertThat(NothingNotable.matches(request("특이사항 없음", "손을 잡고 30까지 세자 진정됨"))).isFalse();
    }

    @Test
    void 일지가_없는_묶음은_맞지_않는다() {
        assertThat(NothingNotable.matches(request())).isFalse();
    }

    @Test
    void 응답은_본문과_문장이_비고_묶음_일지가_모두_uncovered인_요약할_내용_없음이다() {
        SummaryAgentRequest request = new SummaryAgentRequest(8L, "하늘", "2026-11-09", 3L, "햇살센터",
                List.of(new SummaryAgentRequest.Source(11L, "특이사항 없음", "2026-11-09"),
                        new SummaryAgentRequest.Source(12L, "이상 무", "2026-11-09")),
                List.of());

        SummaryAgentReply reply = NothingNotable.reply(request);
        SummaryAgentResponse response = reply.response();

        assertThat(response.isNoContent()).isTrue();
        assertThat(response.content()).isEmpty();
        assertThat(response.claims()).isEmpty();
        assertThat(response.coveredEntryIds()).isEmpty();
        assertThat(response.uncoveredEntryIds().toString()).isEqualTo("[11,12]");
        assertThat(response.childId()).isEqualTo(8L);
        assertThat(reply.rawJson()).contains("backend_rule");
        assertThat(new SummaryEvidenceChecker().findProblem(
                new SummaryGroup(8L, LocalDate.of(2026, 11, 9), 3L), Set.of(11L, 12L),
                Map.of(11L, "특이사항 없음", 12L, "이상 무"), response)).isEmpty();
    }
}
