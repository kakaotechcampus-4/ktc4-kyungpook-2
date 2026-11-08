package com.itda.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.response.agent.AiProgressItem;
import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse.Item;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.ProgressEntryKey;
import com.itda.backend.service.consent.ConsentStateReader;

@ExtendWith(MockitoExtension.class)
class AiProgressTranslatorTest {

    private static final LocalDate NOV_8 = LocalDate.of(2026, 11, 8);
    private static final LocalDate NOV_9 = LocalDate.of(2026, 11, 9);
    private static final LocalDate WEEK = LocalDate.of(2026, 11, 3);

    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private ChildOrganizationRepository childOrganizationRepository;
    @Mock
    private ConsentStateReader consentStateReader;

    private AiProgressTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new AiProgressTranslator(journalEntryRepository, childOrganizationRepository, consentStateReader);
    }

    private static AiProgressItem entry(String agent, long entryId, String node, int sec) {
        return new AiProgressItem(agent, entryId, null, null, null, null, node, sec);
    }

    private static AiProgressItem summary(long childId, LocalDate date, long institutionId, String node, int sec) {
        return new AiProgressItem("summary", null, childId, date, institutionId, null, node, sec);
    }

    private static AiProgressItem insight(long childId, LocalDate from, String node, int sec) {
        return new AiProgressItem("insight", null, childId, null, null, from, node, sec);
    }

    @Test
    void 매칭은_아이_없이_날짜_줄이고_검증은_아이와_날짜_줄이다() {
        given(journalEntryRepository.findProgressKeys(any())).willReturn(List.of(
                new ProgressEntryKey(120L, null, NOV_9, "3"),
                new ProgressEntryKey(123L, 7L, NOV_8, "3")));

        Map<Long, List<Item>> result = translator.translate(List.of(
                entry("matching", 120L, "llm_judge", 7),
                entry("validation", 123L, "act", 5)));

        assertThat(result).containsOnlyKeys(3L);
        assertThat(result.get(3L)).containsExactly(
                new Item(Kind.ENTRY, null, NOV_9, null, "matching", "llm_judge", 7),
                new Item(Kind.ENTRY, "7", NOV_8, null, "validation", "act", 5));
    }

    @Test
    void 매칭_중_아이가_정해졌어도_매칭_줄은_아이_없이_붙인다() {
        given(journalEntryRepository.findProgressKeys(any()))
                .willReturn(List.of(new ProgressEntryKey(120L, 7L, NOV_9, "3")));

        assertThat(translator.translate(List.of(entry("matching", 120L, "decide", 9))).get(3L))
                .containsExactly(new Item(Kind.ENTRY, null, NOV_9, null, "matching", "decide", 9));
    }

    @Test
    void 요약은_DB를_보지_않고_그대로_옮긴다() {
        Map<Long, List<Item>> result = translator.translate(List.of(summary(7L, NOV_8, 3L, "write", 12)));

        assertThat(result.get(3L)).containsExactly(new Item(Kind.ENTRY, "7", NOV_8, null, "summary", "write", 12));
        verify(journalEntryRepository, never()).findProgressKeys(any());
        verify(childOrganizationRepository, never()).findByChildIdInAndDeletedAtIsNull(any());
        verify(consentStateReader, never()).statesOf(any());
    }

    @Test
    void 리포트는_그_아이와_연결되고_공유_동의가_켜진_기관_모두에_주_줄로_붙인다() {
        given(childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(Set.of(7L)))
                .willReturn(List.of(ChildOrganization.of(7L, 3L), ChildOrganization.of(7L, 4L)));
        given(consentStateReader.statesOf(Set.of(7L)))
                .willReturn(Map.of(7L, Map.of(3L, ConsentState.GRANTED, 4L, ConsentState.GRANTED)));

        Map<Long, List<Item>> result = translator.translate(List.of(insight(7L, WEEK, "find_claims", 31)));

        Item week = new Item(Kind.WEEK, "7", null, WEEK, "insight", "find_claims", 31);
        assertThat(result).containsOnlyKeys(3L, 4L);
        assertThat(result.get(3L)).containsExactly(week);
        assertThat(result.get(4L)).containsExactly(week);
        verify(journalEntryRepository, never()).findProgressKeys(any());
    }

    /** 진행 현황(O-80)의 주 줄과 같은 기준이다 — 동의를 끄거나 받지 않은 기관에는 응답에도 넣지 않는다. */
    @Test
    void 리포트는_공유_동의가_꺼졌거나_없는_기관에는_붙이지_않는다() {
        given(childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(Set.of(7L)))
                .willReturn(List.of(ChildOrganization.of(7L, 3L), ChildOrganization.of(7L, 4L),
                        ChildOrganization.of(7L, 5L)));
        given(consentStateReader.statesOf(Set.of(7L)))
                .willReturn(Map.of(7L, Map.of(3L, ConsentState.GRANTED, 4L, ConsentState.REVOKED)));

        Map<Long, List<Item>> result = translator.translate(List.of(insight(7L, WEEK, "plan", 3)));

        assertThat(result).containsOnlyKeys(3L);
    }

    @Test
    void 다른_기관의_일은_섞이지_않는다() {
        given(journalEntryRepository.findProgressKeys(any())).willReturn(List.of(
                new ProgressEntryKey(120L, null, NOV_9, "3"),
                new ProgressEntryKey(130L, null, NOV_9, "5")));

        Map<Long, List<Item>> result = translator.translate(List.of(
                entry("matching", 120L, "extract", 1),
                entry("matching", 130L, "decide", 2),
                summary(8L, NOV_8, 5L, "gather", 3)));

        assertThat(result.get(3L)).extracting(Item::node).containsExactly("extract");
        assertThat(result.get(5L)).extracting(Item::node).containsExactly("decide", "gather");
    }

    @Test
    void 같은_줄에_여럿이면_가장_앞선_단계를_고른다() {
        given(journalEntryRepository.findProgressKeys(any())).willReturn(List.of(
                new ProgressEntryKey(121L, 7L, NOV_8, "3"),
                new ProgressEntryKey(122L, 7L, NOV_8, "3"),
                new ProgressEntryKey(123L, 7L, NOV_8, "3")));

        Map<Long, List<Item>> result = translator.translate(List.of(
                entry("validation", 121L, "plan", 20),
                entry("validation", 122L, "reflect", 4),
                entry("validation", 123L, "act", 9)));

        assertThat(result.get(3L)).containsExactly(
                new Item(Kind.ENTRY, "7", NOV_8, null, "validation", "reflect", 4));
    }

    /** 진행 현황(O-80)은 묶음에 검증 중인 일지가 하나라도 있으면 그 줄을 "AI 확인"으로 둔다 — 단계 문구도 그쪽을 따른다. */
    @Test
    void 같은_줄에_다른_에이전트가_겹치면_줄_상태처럼_더_이른_에이전트를_고른다() {
        given(journalEntryRepository.findProgressKeys(any()))
                .willReturn(List.of(new ProgressEntryKey(123L, 7L, NOV_8, "3")));

        Map<Long, List<Item>> result = translator.translate(List.of(
                summary(7L, NOV_8, 3L, "assemble", 40),
                entry("validation", 123L, "perceive", 1)));

        assertThat(result.get(3L)).containsExactly(
                new Item(Kind.ENTRY, "7", NOV_8, null, "validation", "perceive", 1));
    }

    @Test
    void 같은_단계면_더_오래_걸린_것을_고른다() {
        given(journalEntryRepository.findProgressKeys(any())).willReturn(List.of(
                new ProgressEntryKey(120L, null, NOV_9, "3"),
                new ProgressEntryKey(121L, null, NOV_9, "3")));

        Map<Long, List<Item>> result = translator.translate(List.of(
                entry("matching", 120L, "llm_judge", 3),
                entry("matching", 121L, "llm_judge", 11)));

        assertThat(result.get(3L)).extracting(Item::elapsedSec).containsExactly(11);
    }

    @Test
    void 리포트의_도구_사용은_refine보다_앞선_단계다() {
        given(childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(Set.of(7L)))
                .willReturn(List.of(ChildOrganization.of(7L, 3L)));
        given(consentStateReader.statesOf(Set.of(7L))).willReturn(Map.of(7L, Map.of(3L, ConsentState.GRANTED)));

        Map<Long, List<Item>> result = translator.translate(List.of(
                insight(7L, WEEK, "refine", 40),
                insight(7L, WEEK, "find_claims", 31)));

        assertThat(result.get(3L)).extracting(Item::node).containsExactly("find_claims");
    }

    @Test
    void 모르는_에이전트와_찾지_못한_일지는_버린다() {
        given(journalEntryRepository.findProgressKeys(any())).willReturn(List.of());

        Map<Long, List<Item>> result = translator.translate(List.of(
                entry("validation", 999L, "act", 5),
                new AiProgressItem("notify", null, 7L, null, null, null, "send", 1)));

        assertThat(result).isEmpty();
    }

    @Test
    void 키가_빠진_줄은_버린다() {
        Map<Long, List<Item>> result = translator.translate(List.of(
                new AiProgressItem("matching", null, null, null, null, null, "extract", 1),
                new AiProgressItem("summary", null, 7L, NOV_8, null, null, "write", 1),
                new AiProgressItem("insight", null, null, null, null, WEEK, "plan", 1)));

        assertThat(result).isEmpty();
        verify(journalEntryRepository, never()).findProgressKeys(any());
        verify(childOrganizationRepository, never()).findByChildIdInAndDeletedAtIsNull(any());
        verify(consentStateReader, never()).statesOf(any());
    }

    @Test
    void 아무것도_안_돌면_DB를_보지_않는다() {
        assertThat(translator.translate(List.of())).isEmpty();
        verify(journalEntryRepository, never()).findProgressKeys(any());
        verify(childOrganizationRepository, never()).findByChildIdInAndDeletedAtIsNull(any());
        verify(consentStateReader, never()).statesOf(any());
    }
}
