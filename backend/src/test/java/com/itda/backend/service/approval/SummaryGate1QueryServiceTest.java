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
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ClaimCard;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ClaimEvidence;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ReviewFlag;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.SourceEntry;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.UncoveredEntry;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.summary.SummaryProperties;
import com.itda.backend.service.summary.SummaryRunRequests;
import com.itda.backend.service.user.UserService;

@DataJpaTest
@ActiveProfiles("test")
@Import({SummaryGate1QueryService.class, SummaryRunRequests.class, ConsentStateReader.class})
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

    @MockitoBean
    private SummaryProperties summaryProperties;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long junho;
    private SharingConsent consent;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        junho = em.persist(Child.of("김준호", LocalDate.of(2020, 3, 1))).getId();
        consent = em.persistAndFlush(SharingConsent.grant(junho, 100L, ORG, List.of(ConsentField.DAILY_SUMMARY)));
    }

    private JournalEntry entry(String content) {
        JournalEntry entry = JournalEntry.of(1L, DATE, content, 1);
        entry.startMatching();
        entry.confirmMatch(junho);
        entry.startValidating();
        entry.passValidation();
        entry.startSummarizing();
        return em.persistAndFlush(entry);
    }

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
    void 동의를_철회한_아이의_요약은_검토_대기와_보류함에서_뺀다() {
        summary(ORG, DATE, "[]", List.of(), List.of());
        summary(ORG, DATE.plusDays(1), "[]", List.of(), List.of()).hold();
        consent.revoke(100L, ConsentRevokeReason.GUARDIAN_REVOKED, DATE.atTime(18, 0));
        em.flush();

        assertThat(service.getSummaries(TEACHER, "pending")).isEmpty();
        assertThat(service.getSummaries(TEACHER, "hold")).isEmpty();
    }

    @Test
    void 요약할_내용_없음은_표시와_함께_그날_일지_원본을_모두_보여준다() {
        JournalEntry first = entry("특이사항 없음");
        JournalEntry second = entry("특이사항 없음.");
        SummaryResult empty = em.persistAndFlush(SummaryResult.noContent(junho, DATE, ORG, 1,
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
    void 요약에_든_일지와_안_든_일지의_원문을_모두_id_순으로_보여주고_삭제된_일지는_뺀다() {
        JournalEntry used = entry("블록을 다섯 층까지 쌓았다.");
        JournalEntry left = entry("낮잠을 잤다.");
        JournalEntry deleted = entry("지워진 기록");
        deleted.delete();
        summary(ORG, DATE, "[]", List.of(used), List.of(left, deleted));

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.entries()).containsExactly(
                new SourceEntry(used.getId(), "블록을 다섯 층까지 쌓았다."),
                new SourceEntry(left.getId(), "낮잠을 잤다."));
    }

    @Test
    void 요약_문장을_카드로_고칠_수_있게_순서와_글을_보여준다() {
        summary(ORG, DATE, "[{\"text\":\"블록을 쌓았다.\",\"evidence\":[]},"
                + "{\"text\":\"낮잠을 잤다.\",\"evidence\":[]}]", List.of(), List.of());

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.claims()).containsExactly(new ClaimCard(0, "블록을 쌓았다.", List.of()),
                new ClaimCard(1, "낮잠을 잤다.", List.of()));
    }

    @Test
    void 요약_문장마다_AI가_든_근거_일지와_인용을_보여준다() {
        JournalEntry blocks = entry("블록을 높이 쌓았다.");
        summary(ORG, DATE, "[{\"text\":\"블록을 쌓았다.\",\"evidence\":["
                + "{\"journal_entry_id\":" + blocks.getId() + ",\"quote\":\"블록을 높이 쌓았다\","
                + "\"span\":{\"start\":0,\"end\":10}}]}]", List.of(blocks), List.of());

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.claims()).containsExactly(new ClaimCard(0, "블록을 쌓았다.",
                List.of(new ClaimEvidence(blocks.getId(), "블록을 높이 쌓았다"))));
    }

    @Test
    void 선생님이_고치거나_새로_쓴_문장은_근거를_비워_보여준다() {
        JournalEntry blocks = entry("블록을 높이 쌓았다.");
        String evidence = "[{\"journal_entry_id\":" + blocks.getId() + ",\"quote\":\"블록\","
                + "\"span\":{\"start\":0,\"end\":2}}]";
        summary(ORG, DATE, "[{\"text\":\"블록을 아주 높이 쌓았다.\",\"evidence\":" + evidence + ",\"source\":\"EDITED\"},"
                + "{\"text\":\"웃었다.\",\"evidence\":[{\"journal_entry_id\":null,\"quote\":\"웃었다.\"}],"
                + "\"source\":\"TEACHER\"},"
                + "{\"text\":\"블록을 쌓았다.\",\"evidence\":" + evidence + ",\"source\":\"AI\"}]",
                List.of(blocks), List.of());

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.claims()).extracting(ClaimCard::evidence).containsExactly(
                List.of(), List.of(), List.of(new ClaimEvidence(blocks.getId(), "블록")));
    }

    @Test
    void 문장이_아닌_값이_섞인_claims_는_카드로_보여주지_않는다() {
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
                .containsExactly(new ReviewFlag(overlapped.getId(), List.of("위험행동표현", "추측성표현"),
                        List.of("위험 행동이 그대로 묘사돼 있어요", "관찰되지 않은 내용을 추측한 표현이 있어요"),
                        List.of(0)));
    }

    @Test
    void 요약_근거와_겹치지_않는_REVIEW_구간이면_숨긴다() {
        JournalEntry apart = entry("블록 놀이를 했다. 집에 가고 싶다고 울었다.");
        validated(apart, ValidationVerdict.REVIEW, "[\"감정적표현\"]", "[{\"start\":11,\"end\":24}]");
        summary(ORG, DATE, "[" + claim(apart, 0, 11) + "]", List.of(apart), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags()).isEmpty();
    }

    @Test
    void REVIEW_구간이_비어_있으면_어디가_문제인지_몰라_보여준다() {
        JournalEntry unknown = entry("누구 이야기인지 모를 기록");
        validated(unknown, ValidationVerdict.REVIEW, "[\"대상불명확\"]", "[]");
        summary(ORG, DATE, "[" + claim(unknown, 0, 4) + "]", List.of(unknown), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags())
                .containsExactly(new ReviewFlag(unknown.getId(), List.of("대상불명확"),
                        List.of("어느 아이의 기록인지 분명하지 않아요"), List.of()));
    }

    @Test
    void 검증_구간과_겹친_문장_번호만_오름차순으로_준다() {
        JournalEntry pushed = entry("블록 놀이 중 친구를 밀었다. 점심을 먹었다.");
        validated(pushed, ValidationVerdict.REVIEW, "[\"위험행동표현\"]", "[{\"start\":5,\"end\":15}]");
        summary(ORG, DATE, "[" + claim(pushed, 0, 6) + "," + claim(pushed, 17, 23) + "," + claim(pushed, 10, 16) + "]",
                List.of(pushed), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags())
                .singleElement().extracting(ReviewFlag::claimIndexes).isEqualTo(List.of(0, 2));
    }

    @Test
    void 한_문장이_구간_둘로_겹쳐도_번호는_한_번이다() {
        JournalEntry pushed = entry("블록 놀이 중 친구를 밀었다. 다시 밀었다.");
        validated(pushed, ValidationVerdict.REVIEW, "[\"위험행동표현\"]",
                "[{\"start\":5,\"end\":15},{\"start\":17,\"end\":23}]");
        String twoSpans = "{\"text\":\"문장\",\"evidence\":["
                + "{\"journal_entry_id\":" + pushed.getId() + ",\"quote\":\"q\",\"span\":{\"start\":0,\"end\":8}},"
                + "{\"journal_entry_id\":" + pushed.getId() + ",\"quote\":\"q\",\"span\":{\"start\":18,\"end\":22}}]}";
        summary(ORG, DATE, "[" + twoSpans + "]", List.of(pushed), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags())
                .singleElement().extracting(ReviewFlag::claimIndexes).isEqualTo(List.of(0));
    }

    @Test
    void 일지가_둘이면_경고마다_자기_일지와_겹친_문장만_든다() {
        JournalEntry morning = entry("블록 놀이 중 친구를 밀었다.");
        JournalEntry afternoon = entry("간식을 먹고 아마 졸렸던 것 같다.");
        validated(morning, ValidationVerdict.REVIEW, "[\"위험행동표현\"]", "[{\"start\":5,\"end\":15}]");
        validated(afternoon, ValidationVerdict.REVIEW, "[\"추측성표현\"]", "[{\"start\":7,\"end\":18}]");
        summary(ORG, DATE, "[" + claim(morning, 0, 8) + "," + claim(afternoon, 5, 12) + "," + claim(afternoon, 0, 4) + "]",
                List.of(morning, afternoon), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags())
                .extracting(ReviewFlag::journalEntryId, ReviewFlag::claimIndexes)
                .containsExactly(tuple(morning.getId(), List.of(0)), tuple(afternoon.getId(), List.of(1)));
    }

    @Test
    void 카드로_못_읽는_요약은_경고와_사유는_그대로고_번호만_비어_있다() {
        JournalEntry pushed = entry("블록 놀이 중 친구를 밀었다.");
        validated(pushed, ValidationVerdict.REVIEW, "[\"위험행동표현\"]", "[{\"start\":5,\"end\":15}]");
        summary(ORG, DATE, "[" + claim(pushed, 0, 8) + ", 3]", List.of(pushed), List.of());

        Gate1SummaryResponse item = service.getSummaries(TEACHER, "pending").get(0);

        assertThat(item.claims()).isEmpty();
        assertThat(item.reviewFlags()).containsExactly(new ReviewFlag(pushed.getId(), List.of("위험행동표현"),
                List.of("위험 행동이 그대로 묘사돼 있어요"), List.of()));
    }

    @Test
    void 고친_문장의_남은_근거로만_겹치면_경고는_그대로고_번호만_비어_있다() {
        JournalEntry pushed = entry("블록 놀이 중 친구를 밀었다.");
        validated(pushed, ValidationVerdict.REVIEW, "[\"위험행동표현\"]", "[{\"start\":5,\"end\":15}]");
        summary(ORG, DATE, "[" + edited(claim(pushed, 0, 8)) + "]", List.of(pushed), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags())
                .containsExactly(new ReviewFlag(pushed.getId(), List.of("위험행동표현"),
                        List.of("위험 행동이 그대로 묘사돼 있어요"), List.of()));
    }

    @Test
    void AI_문장과_고친_문장이_함께_겹치면_AI_문장_번호만_든다() {
        JournalEntry pushed = entry("블록 놀이 중 친구를 밀었다.");
        validated(pushed, ValidationVerdict.REVIEW, "[\"위험행동표현\"]", "[{\"start\":5,\"end\":15}]");
        summary(ORG, DATE, "[" + edited(claim(pushed, 0, 8)) + "," + claim(pushed, 6, 12) + "]",
                List.of(pushed), List.of());

        assertThat(service.getSummaries(TEACHER, "pending").get(0).reviewFlags())
                .singleElement().extracting(ReviewFlag::claimIndexes).isEqualTo(List.of(1));
    }

    private static String edited(String claim) {
        return claim.substring(0, claim.length() - 1) + ",\"source\":\"EDITED\"}";
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

        assertThat(item.reviewFlags()).containsExactly(new ReviewFlag(broken.getId(), List.of(),
                List.of("검증에서 문제가 발견됐어요. 원본을 확인해 주세요"), List.of()));
        assertThat(item.reviewReasons()).isEmpty();
        assertThat(item.claims()).isEmpty();
    }

    @Test
    void 검토_대기와_보류함_말고는_조회하지_않는다() {
        for (String unsupported : new String[] {"approved", "rejected", "HOLD", "GATE1_PENDING"}) {
            assertThatThrownBy(() -> service.getSummaries(TEACHER, unsupported))
                    .isInstanceOf(SummaryException.class)
                    .extracting(e -> ((SummaryException) e).getErrorCode())
                    .isEqualTo(SummaryErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void AI가_어느_목록에도_넣지_않은_일지도_요약에_안_들어간_일지로_보여준다() {
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

    private void requestRun(LocalDate date) {
        em.persistAndFlush(SummaryRunRequest.of(junho, date, ORG, date.atTime(17, 0)));
    }

    @Test
    void 요약을_누른_묶음에_아직_요약에_반영되지_않은_일지가_있으면_처리_중으로_표시한다() {
        summary(ORG, DATE, "[]", List.of(), List.of());
        requestRun(DATE);
        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isFalse();

        validatedInSameGroup();

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isTrue();
    }

    @Test
    void 요약을_누르지_않았으면_새_일지가_있어도_처리_중이_아니다() {
        summary(ORG, DATE, "[]", List.of(), List.of());
        validatedInSameGroup();

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isFalse();
    }

    @Test
    void 지금_요약_중인_일지가_있으면_누른_요청이_없어도_처리_중이다() {
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
        summary(ORG, DATE, "[]", List.of(), List.of());
        requestRun(DATE);
        em.persistAndFlush(JournalEntry.of(schoolRecord(), DATE, "누구 기록인지 모름", 3));

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isTrue();
    }

    @Test
    void 자동_요약이_켜져_있으면_누르지_않아도_반영되지_않은_일지가_있을_때_처리_중이다() {
        given(summaryProperties.autoRun()).willReturn(true);
        summary(ORG, DATE, "[]", List.of(), List.of());
        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isFalse();

        validatedInSameGroup();

        assertThat(service.getSummaries(TEACHER, "pending").get(0).processing()).isTrue();
    }

    @Test
    void 자동_요약이_켜져_있으면_누르지_않아도_같은_날_매칭_전_일지가_있을_때_처리_중이다() {
        given(summaryProperties.autoRun()).willReturn(true);
        summary(ORG, DATE, "[]", List.of(), List.of());
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

    private long queriesForPendingList(int count) {
        Long record = schoolRecord();
        for (int i = 0; i < count; i++) {
            LocalDate date = DATE.minusDays(i);
            JournalEntry covered = entry("블록 놀이를 했다.");
            JournalEntry uncovered = entry("낮잠을 잤다.");
            summary(ORG, date, "[" + claim(covered, 0, 4) + "]", List.of(covered), List.of(uncovered));
            validated(covered, ValidationVerdict.REVIEW, "[\"추측성표현\"]", "[]");
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

        assertThat(twenty).as("요약 1개: %d번, 요약 20개: %d번", one, twenty).isEqualTo(one);
    }

    private List<Gate1BoardItem> fromLists() {
        return java.util.stream.Stream.concat(service.getSummaries(TEACHER, "pending").stream(),
                        service.getSummaries(TEACHER, "hold").stream())
                .map(s -> new Gate1BoardItem(s.id(), s.childId(), s.childName(), s.entryDate(),
                        SummaryStatus.valueOf(s.status()), s.processing(), s.noContent()))
                .toList();
    }

    @Test
    void 보드는_검토_대기와_보류함_목록과_같은_요약을_같은_판정으로_날짜순에_준다() {
        SummaryResult processing = summary(ORG, DATE, "[]", List.of(), List.of());
        requestRun(DATE);
        validatedInSameGroup();
        SummaryResult empty = em.persistAndFlush(SummaryResult.noContent(junho, DATE.plusDays(1), ORG, 1, "[]", "{}"));
        SummaryResult held = summary(ORG, DATE.minusDays(1), "[]", List.of(), List.of());
        held.hold();
        summary(ORG, DATE.minusDays(2), "[]", List.of(), List.of()).approve();
        summary(4L, DATE, "[]", List.of(), List.of());
        em.flush();

        List<Gate1BoardItem> board = service.getBoardItems(ORG);

        assertThat(board).extracting(Gate1BoardItem::id)
                .containsExactly(held.getId(), processing.getId(), empty.getId());
        assertThat(board).containsExactlyInAnyOrderElementsOf(fromLists());
        assertThat(board).extracting(Gate1BoardItem::childName, Gate1BoardItem::status, Gate1BoardItem::processing,
                        Gate1BoardItem::noContent)
                .containsExactly(
                        tuple("김준호", SummaryStatus.HOLD, false, false),
                        tuple("김준호", SummaryStatus.GATE1_PENDING, true, false),
                        tuple("김준호", SummaryStatus.GATE1_PENDING, false, true));
    }

    @Test
    void 보드도_동의를_철회한_아이의_요약을_뺀다() {
        summary(ORG, DATE, "[]", List.of(), List.of());
        summary(ORG, DATE.plusDays(1), "[]", List.of(), List.of()).hold();
        consent.revoke(100L, ConsentRevokeReason.GUARDIAN_REVOKED, DATE.atTime(18, 0));
        em.flush();

        assertThat(service.getBoardItems(ORG)).isEmpty();
    }

    @Test
    void 보드도_최신_판이_아닌_요약은_처리_중으로_표시하지_않는다() {
        SummaryResult older = summary(ORG, DATE, "[]", List.of(), List.of());
        SummaryResult newer = em.persistAndFlush(SummaryResult.of(junho, DATE, ORG, 2, "2판", "[]", "[]", "[]", "{}"));
        requestRun(DATE);
        validatedInSameGroup();

        assertThat(service.getBoardItems(ORG))
                .extracting(Gate1BoardItem::id, Gate1BoardItem::processing)
                .containsExactly(tuple(older.getId(), false), tuple(newer.getId(), true));
        assertThat(service.getBoardItems(ORG)).containsExactlyInAnyOrderElementsOf(fromLists());
    }

    @Test
    void 보드는_요약_본문_일지_원문_검증_결과를_읽지_않는다() {
        queriesForPendingList(3);
        em.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<Gate1BoardItem> board = service.getBoardItems(ORG);

        long summaries = statistics.getEntityStatistics(SummaryResult.class.getName()).getLoadCount();
        long entries = statistics.getEntityStatistics(JournalEntry.class.getName()).getLoadCount();
        long validations = statistics.getEntityStatistics(ValidationResult.class.getName()).getLoadCount();
        statistics.setStatisticsEnabled(false);
        assertThat(board).hasSize(3).allSatisfy(item -> assertThat(item.processing()).isTrue());
        assertThat(List.of(summaries, entries, validations)).containsOnly(0L);
    }
}
