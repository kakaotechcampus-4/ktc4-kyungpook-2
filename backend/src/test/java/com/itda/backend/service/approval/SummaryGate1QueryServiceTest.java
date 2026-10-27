package com.itda.backend.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ClaimCard;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ReviewFlag;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.UncoveredEntry;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.service.summary.SummaryRunRequests;
import com.itda.backend.service.user.UserService;

/** Gate 1 검토 목록 — 요약 본문, 다른 아이 이름 경고, 요약에 안 들어간 일지, 요약에 들어간 원본의 REVIEW 사유. */
@DataJpaTest
@ActiveProfiles("test")
@Import({SummaryGate1QueryService.class, SummaryRunRequests.class})
class SummaryGate1QueryServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Long ORG = 3L;
    private static final String TEACHER = "99";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SummaryGate1QueryService service;

    @MockitoBean
    private UserService userService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long junho;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        junho = em.persist(Child.of("김준호", LocalDate.of(2020, 3, 1))).getId();
    }

    /** 요약 워커가 집어 간 일지. 요약이 저장되면 {@link #summary} 가 그 요약을 가리키게 한다. */
    private JournalEntry entry(String content) {
        JournalEntry entry = JournalEntry.of(1L, DATE, content, 1);
        entry.startMatching();
        entry.confirmMatch(junho);
        entry.startValidating();
        entry.passValidation();
        entry.startSummarizing();
        return em.persistAndFlush(entry);
    }

    /** 요약을 저장하고, 보낸 일지(covered·uncovered)가 그 요약을 가리키게 한다 — 레코더와 같다. */
    private SummaryResult summary(Long institutionId, LocalDate date, String claims, List<JournalEntry> covered,
            List<JournalEntry> uncovered) {
        SummaryResult summary = em.persistAndFlush(SummaryResult.of(junho, date, institutionId, 1, "요약 본문", claims,
                ids(covered), ids(uncovered), "{}"));
        covered.forEach(e -> link(e, summary));
        uncovered.forEach(e -> link(e, summary));
        em.flush();
        return summary;
    }

    private void link(JournalEntry entry, SummaryResult summary) {
        entry.completeSummary(summary.getId());
        em.merge(entry);
    }

    private static String ids(List<JournalEntry> entries) {
        return entries.stream().map(e -> String.valueOf(e.getId())).reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]").orElse("[]");
    }

    /** 한 일지를 근거로 든 claim 하나. span 은 그 일지 원문 기준 [start, end). */
    private static String claim(JournalEntry entry, int start, int end) {
        return "{\"text\":\"문장\",\"evidence\":[{\"journal_entry_id\":" + entry.getId()
                + ",\"quote\":\"q\",\"span\":{\"start\":" + start + ",\"end\":" + end + "}}]}";
    }

    private void validated(JournalEntry entry, ValidationVerdict verdict, String issueTypes, String evidence) {
        em.persistAndFlush(ValidationResult.of(entry.getId(), null, junho, verdict, issueTypes, evidence, "{}"));
    }

    @Test
    void 우리_기관의_Gate1_대기_요약만_오래된_날짜부터_보여준다() {
        SummaryResult later = summary(ORG, DATE.plusDays(1), "[]", List.of(), List.of());
        SummaryResult earlier = summary(ORG, DATE, "[]", List.of(), List.of());
        summary(4L, DATE, "[]", List.of(), List.of());
        SummaryResult held = summary(ORG, DATE.minusDays(1), "[]", List.of(), List.of());
        held.hold();
        em.flush();

        List<Gate1SummaryResponse> pending = service.getSummaries(TEACHER, "pending");

        assertThat(pending).extracting(Gate1SummaryResponse::id).containsExactly(earlier.getId(), later.getId());
        assertThat(pending.get(0).childName()).isEqualTo("김준호");
        assertThat(pending.get(0).content()).isEqualTo("요약 본문");
        assertThat(pending.get(0).status()).isEqualTo("GATE1_PENDING");
    }

    @Test
    void 보류함은_보류된_요약만_보여준다() {
        summary(ORG, DATE, "[]", List.of(), List.of());
        SummaryResult held = summary(ORG, DATE.plusDays(1), "[]", List.of(), List.of());
        held.hold();
        em.flush();

        assertThat(service.getSummaries(TEACHER, "hold"))
                .extracting(Gate1SummaryResponse::id).containsExactly(held.getId());
    }

    @Test
    void 요약할_내용_없음은_표시와_함께_그날_일지_원본을_모두_보여준다() {
        // 교사가 "왜 없지?" 할 때 바로 원본을 볼 수 있어야 한다 (#244). covered 가 비어 일지가 모두 uncovered 로 간다.
        JournalEntry first = entry("특이사항 없음");
        JournalEntry second = entry("특이사항 없음.");
        SummaryResult empty = em.persistAndFlush(SummaryResult.noContent(junho, DATE, ORG, 1, "[]",
                ids(List.of(first, second)), "{}"));
        link(first, empty);
        link(second, empty);
        em.flush();

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.noContent()).isTrue();
        assertThat(item.content()).isEmpty();
        assertThat(item.claims()).isEmpty();
        assertThat(item.uncoveredEntries())
                .extracting(Gate1SummaryResponse.UncoveredEntry::journalEntryId,
                        Gate1SummaryResponse.UncoveredEntry::content)
                .containsExactlyInAnyOrder(tuple(first.getId(), "특이사항 없음"), tuple(second.getId(), "특이사항 없음."));
        assertThat(item.reviewFlags()).isEmpty();
    }

    @Test
    void 일반_요약은_요약할_내용_없음이_아니다() {
        summary(ORG, DATE, "[]", List.of(), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).noContent()).isFalse();
    }

    @Test
    void 다른_아이_이름이_남았다는_경고를_보여준다() {
        SummaryResult s = summary(ORG, DATE, "[]", List.of(), List.of());
        s.markReview(true, "[\"다른아동이름\"]");
        em.flush();

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.needsReview()).isTrue();
        assertThat(item.reviewReasons()).containsExactly("다른아동이름");
    }

    @Test
    void 요약에_안_들어간_일지와_원문을_보여주고_삭제된_일지는_뺀다() {
        JournalEntry left = entry("낮잠을 잤다.");
        JournalEntry deleted = entry("지워진 기록");
        deleted.delete();
        summary(ORG, DATE, "[]", List.of(), List.of(left, deleted));

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.uncoveredEntries()).containsExactly(new UncoveredEntry(left.getId(), "낮잠을 잤다."));
    }

    @Test
    void 요약_문장을_카드로_고칠_수_있게_순서와_글을_보여준다() {
        summary(ORG, DATE, "[{\"text\":\"블록을 쌓았다.\",\"evidence\":[]},"
                + "{\"text\":\"낮잠을 잤다.\",\"evidence\":[]}]", List.of(), List.of());

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        // index 는 결정(O-31) 카드에 그대로 돌려보내는 번호다 — claims 배열 순서다.
        assertThat(item.claims()).containsExactly(new ClaimCard(0, "블록을 쌓았다."), new ClaimCard(1, "낮잠을 잤다."));
    }

    @Test
    void 문장이_아닌_값이_섞인_claims_는_카드로_보여주지_않는다() {
        // 결정(O-31)도 같은 기준으로 문장이 없다고 본다. 카드를 보여주면 그대로 보내도 400 이 난다.
        summary(ORG, DATE, "[{\"text\":\"블록을 쌓았다.\"}, \"문장\"]", List.of(), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).claims()).isEmpty();
    }

    @Test
    void 요약_근거와_겹치는_REVIEW_구간이_있는_일지는_그_일지의_사유를_모두_보여준다() {
        JournalEntry overlapped = entry("블록 놀이 중 친구를 밀었다.");
        validated(overlapped, ValidationVerdict.REVIEW, "[\"위험행동표현\",\"추측성표현\"]", "[{\"start\":5,\"end\":12}]");
        summary(ORG, DATE, "[" + claim(overlapped, 0, 8) + "]", List.of(overlapped), List.of());

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.reviewFlags())
                .containsExactly(new ReviewFlag(overlapped.getId(), List.of("위험행동표현", "추측성표현")));
    }

    @Test
    void 요약_근거와_겹치지_않는_REVIEW_구간이면_숨긴다() {
        JournalEntry apart = entry("블록 놀이를 했다. 집에 가고 싶다고 울었다.");
        validated(apart, ValidationVerdict.REVIEW, "[\"감정적표현\"]", "[{\"start\":11,\"end\":24}]");
        // 끝은 포함하지 않는다 — [0, 11) 과 [11, 24) 는 겹치지 않는다.
        summary(ORG, DATE, "[" + claim(apart, 0, 11) + "]", List.of(apart), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags()).isEmpty();
    }

    @Test
    void REVIEW_구간이_비어_있으면_어디가_문제인지_몰라_보여준다() {
        JournalEntry unknown = entry("누구 이야기인지 모를 기록");
        validated(unknown, ValidationVerdict.REVIEW, "[\"대상불명확\"]", "[]");
        summary(ORG, DATE, "[" + claim(unknown, 0, 4) + "]", List.of(unknown), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags())
                .containsExactly(new ReviewFlag(unknown.getId(), List.of("대상불명확")));
    }

    @Test
    void 최신_검증_결과가_REVIEW가_아니면_사유를_보여주지_않는다() {
        JournalEntry recheckedPass = entry("블록 놀이를 했다.");
        validated(recheckedPass, ValidationVerdict.REVIEW, "[\"추측성표현\"]", "[]");
        validated(recheckedPass, ValidationVerdict.PASS, "[]", "[]");
        summary(ORG, DATE, "[" + claim(recheckedPass, 0, 4) + "]", List.of(recheckedPass), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags()).isEmpty();
    }

    @Test
    void AI가_쓴_JSON이_깨져_있어도_목록은_보여준다() {
        JournalEntry broken = entry("블록 놀이를 했다.");
        validated(broken, ValidationVerdict.REVIEW, "not json", "not json");
        SummaryResult s = summary(ORG, DATE, "not json", List.of(broken), List.of());
        s.markReview(true, "not json");
        em.flush();

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        // 구간을 못 읽으면 비어 있는 것과 같다 — 숨기기보다 보여주는 쪽이 안전하다.
        assertThat(item.reviewFlags()).containsExactly(new ReviewFlag(broken.getId(), List.of()));
        assertThat(item.reviewReasons()).isEmpty();
        assertThat(item.claims()).isEmpty();
    }

    @Test
    void 검토_대기와_보류함_말고는_조회하지_않는다() {
        // 다른 값을 조용히 대기 목록으로 처리하면 화면이 엉뚱한 목록을 보여준다.
        for (String unsupported : new String[] {"approved", "rejected", "HOLD", "GATE1_PENDING"}) {
            assertThatThrownBy(() -> service.getSummaries(TEACHER, unsupported))
                    .isInstanceOf(SummaryException.class)
                    .extracting(e -> ((SummaryException) e).getErrorCode())
                    .isEqualTo(SummaryErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void AI가_어느_목록에도_넣지_않은_일지도_요약에_안_들어간_일지로_보여준다() {
        // AI 가 covered·uncovered 어디에도 넣지 않아도 그 일지는 이 요약을 가리키고, 승인하면 함께 끝난다.
        // 목록에 안 보이면 교사가 보지도 못한 기록이 승인과 함께 사라진다.
        JournalEntry used = entry("블록 놀이를 했다.");
        JournalEntry forgotten = entry("낮잠을 잤다.");
        SummaryResult s = summary(ORG, DATE, "[" + claim(used, 0, 4) + "]", List.of(used), List.of());
        link(forgotten, s);
        em.flush();

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.uncoveredEntries()).containsExactly(new UncoveredEntry(forgotten.getId(), "낮잠을 잤다."));
    }

    @Test
    void 결정할_때_돌려보낼_판_표시를_준다() {
        SummaryResult s = summary(ORG, DATE, "[]", List.of(), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).version())
                .isEqualTo(Gate1Version.of(s, List.of()));
    }

    private Long schoolRecord() {
        return em.persist(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf", "application/pdf", 12,
                RawRecordStatus.PENDING)).getId();
    }

    /** 교사가 그 묶음에 [요약]을 눌렀다 (#181). 누르지 않으면 요약은 바뀌지 않는다. */
    private void requestRun(LocalDate date) {
        em.persistAndFlush(SummaryRunRequest.of(junho, date, ORG, date.atTime(17, 0)));
    }

    @Test
    void 요약을_누른_묶음에_아직_요약에_반영되지_않은_일지가_있으면_처리_중으로_표시한다() {
        summary(ORG, DATE, "[]", List.of(), List.of());
        requestRun(DATE);
        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isFalse();

        // 검증을 통과해 요약을 기다리는 같은 아이·같은 날 일지. 눌러 뒀으니 곧 이 요약을 다시 만든다.
        validatedInSameGroup();

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isTrue();
    }

    @Test
    void 요약을_누르지_않았으면_새_일지가_있어도_처리_중이_아니다() {
        // 자동 요약이 없어 누르기 전까지 이 요약은 바뀌지 않는다. 처리 중으로 두면 열기·승인을 끝없이 막는다.
        summary(ORG, DATE, "[]", List.of(), List.of());
        validatedInSameGroup();

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isFalse();
    }

    @Test
    void 지금_요약_중인_일지가_있으면_누른_요청이_없어도_처리_중이다() {
        // 워커가 이미 집어 갔다. 결과를 남기면서 처리 표시를 했어도 요약은 곧 바뀐다.
        summary(ORG, DATE, "[]", List.of(), List.of());
        JournalEntry summarizing = JournalEntry.of(schoolRecord(), DATE, "점심을 먹었다.", 2);
        summarizing.startMatching();
        summarizing.confirmMatch(junho);
        summarizing.startValidating();
        summarizing.passValidation();
        summarizing.startSummarizing();
        em.persistAndFlush(summarizing);

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isTrue();
    }

    @Test
    void 요약을_누른_묶음의_같은_기관_같은_날에_매칭_전_일지가_있으면_처리_중으로_표시한다() {
        // 매칭 전이라 누구 일지인지 모른다. 이 아이 일지일 수 있다.
        summary(ORG, DATE, "[]", List.of(), List.of());
        requestRun(DATE);
        em.persistAndFlush(JournalEntry.of(schoolRecord(), DATE, "누구 기록인지 모름", 3));

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isTrue();
    }

    private void validatedInSameGroup() {
        JournalEntry waiting = JournalEntry.of(schoolRecord(), DATE, "점심을 먹었다.", 2);
        waiting.startMatching();
        waiting.confirmMatch(junho);
        waiting.startValidating();
        waiting.passValidation();
        em.persistAndFlush(waiting);
    }

    @Test
    void 보류된_요약은_새_일지가_와도_바뀌지_않으므로_처리_중으로_표시하지_않는다() {
        SummaryResult held = summary(ORG, DATE, "[]", List.of(), List.of());
        held.hold();
        validatedInSameGroup();

        assertThat(service.getSummaries(TEACHER, "hold").get(0).processing()).isFalse();
    }

    @Test
    void 최신_판이_아닌_요약은_처리_중으로_표시하지_않는다() {
        SummaryResult older = summary(ORG, DATE, "[]", List.of(), List.of());
        em.persistAndFlush(SummaryResult.of(junho, DATE, ORG, 2, "2판", "[]", "[]", "[]", "{}"));
        requestRun(DATE);
        validatedInSameGroup();

        List<Gate1SummaryResponse> pending = service.getSummaries(TEACHER, "pending");

        assertThat(pending).filteredOn(item -> item.id().equals(older.getId()))
                .singleElement().satisfies(item -> assertThat(item.processing()).isFalse());
        assertThat(pending).filteredOn(item -> item.revision() == 2)
                .singleElement().satisfies(item -> assertThat(item.processing()).isTrue());
    }

    @Test
    void 다른_아이의_일지가_검증_중이면_이_아이_요약은_처리_중이_아니다() {
        // 매칭이 끝난 일지는 누구 것인지 안다. 다른 아이 일지 때문에 그날 모든 요약의 승인을 막지 않는다.
        summary(ORG, DATE, "[]", List.of(), List.of());
        requestRun(DATE);
        JournalEntry others = JournalEntry.of(schoolRecord(), DATE, "민지가 그림을 그렸다.", 4);
        others.startMatching();
        others.confirmMatch(junho + 1);
        others.startValidating();
        em.persistAndFlush(others);

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isFalse();
    }

    @Test
    void 요약을_누른_묶음에_이_아이의_일지가_검증_중이면_처리_중이다() {
        summary(ORG, DATE, "[]", List.of(), List.of());
        requestRun(DATE);
        JournalEntry mine = JournalEntry.of(schoolRecord(), DATE, "점심을 먹었다.", 4);
        mine.startMatching();
        mine.confirmMatch(junho);
        em.persistAndFlush(mine);

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isTrue();
    }

    /** 검토 대기 요약을 {@code count} 개 만들고(날짜마다 하나, 일지 둘씩) 목록 조회가 날린 SQL 수를 센다. */
    private long queriesForPendingList(int count) {
        Long record = schoolRecord();
        for (int i = 0; i < count; i++) {
            LocalDate date = DATE.minusDays(i);
            JournalEntry covered = entry("블록 놀이를 했다.");
            JournalEntry uncovered = entry("낮잠을 잤다.");
            summary(ORG, date, "[" + claim(covered, 0, 4) + "]", List.of(covered), List.of(uncovered));
            validated(covered, ValidationVerdict.REVIEW, "[\"추측성표현\"]", "[]");
            // 같은 날 요약을 기다리는 일지와 [요약] 요청 — 이 요약은 processing 이다.
            em.persist(SummaryRunRequest.of(junho, date, ORG, date.atTime(17, 0)));
            JournalEntry waiting = JournalEntry.of(record, date, "점심을 먹었다.", 3);
            waiting.startMatching();
            waiting.confirmMatch(junho);
            waiting.startValidating();
            waiting.passValidation();
            em.persist(waiting);
        }
        em.flush();
        em.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        List<Gate1SummaryResponse> items = service.getSummaries(TEACHER, "pending");
        long queries = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);

        assertThat(items).hasSize(count).allSatisfy(item -> assertThat(item.processing()).isTrue());
        return queries;
    }

    @Test
    void 목록_조회의_SQL_수는_요약_수와_상관없이_같다() {
        long one = queriesForPendingList(1);
        em.getEntityManager().createQuery("delete from SummaryResult").executeUpdate();
        em.getEntityManager().createQuery("delete from JournalEntry").executeUpdate();
        em.getEntityManager().createQuery("delete from ValidationResult").executeUpdate();
        em.getEntityManager().createQuery("delete from SummaryRunRequest").executeUpdate();
        long twenty = queriesForPendingList(20);

        // 요약마다 묶음 상태를 따로 물으면 요약 수에 비례해 늘어난다(N+1).
        assertThat(twenty).as("요약 1개: %d번, 요약 20개: %d번", one, twenty).isEqualTo(one);
    }
}
