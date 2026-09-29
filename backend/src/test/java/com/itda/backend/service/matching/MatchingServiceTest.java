package com.itda.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.exception.MatchingTargetException;

@DataJpaTest
@ActiveProfiles("test")
@Import(MatchingService.class)
class MatchingServiceTest {

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private TestEntityManager em;

    private Organization ours;
    private Child yujin;

    @BeforeEach
    void setUp() {
        ours = em.persist(Organization.of("햇살센터", OrganizationType.CENTER, "1234567890"));
        Organization theirs = em.persist(Organization.of("달빛센터", OrganizationType.CENTER, "0987654321"));
        yujin = em.persist(Child.of("임유진", LocalDate.of(2019, 11, 26)));
        Child other = em.persist(Child.of("박서연", LocalDate.of(2020, 5, 5)));
        em.persist(ChildOrganization.of(yujin.getId(), ours.getId()));
        em.persist(ChildOrganization.of(other.getId(), theirs.getId()));
    }

    private RawRecord rawRecord(String institutionId) {
        return em.persist(new RawRecord(institutionId, "임유진_9월.txt", "path", "text/plain", 10L, RawRecordStatus.PENDING));
    }

    private JournalEntry entry(RawRecord rawRecord) {
        return em.persist(JournalEntry.of(rawRecord.getId(), LocalDate.of(2026, 9, 1), "점심을 잘 먹음", 1));
    }

    // RawRecord 에는 아직 표지 힌트를 채우는 메서드가 없다 (업로드 쪽 후속 작업). 테스트에서는 컬럼을 직접 채운다.
    private void setHint(RawRecord rawRecord, String name, String birthdate) {
        em.getEntityManager()
                .createNativeQuery("update raw_record set hint_name = ?1, hint_birthdate = ?2 where id = ?3")
                .setParameter(1, name)
                .setParameter(2, birthdate == null ? null : LocalDate.parse(birthdate))
                .setParameter(3, rawRecord.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    void 대기_일지를_집어_가면서_매칭_중으로_바꾼다() {
        RawRecord rawRecord = rawRecord(String.valueOf(ours.getId()));
        JournalEntry first = entry(rawRecord);
        JournalEntry second = entry(rawRecord);

        List<Long> claimed = matchingService.claimPending(10);
        em.flush();
        em.clear();

        assertThat(claimed).containsExactly(first.getId(), second.getId());
        assertThat(em.find(JournalEntry.class, first.getId()).getStatus()).isEqualTo(JournalEntryStatus.MATCHING);
        assertThat(matchingService.claimPending(10)).isEmpty();
    }

    @Test
    void 요청에는_일지_내용과_그_기관_아동_명단과_표지_힌트가_실린다() {
        RawRecord rawRecord = rawRecord(String.valueOf(ours.getId()));
        JournalEntry entry = entry(rawRecord);
        setHint(rawRecord, "임유진", "2019-11-26");

        MatchingAgentRequest request = matchingService.prepareRequest(entry.getId());

        assertThat(request.journalEntryId()).isEqualTo(entry.getId());
        assertThat(request.content()).isEqualTo("점심을 잘 먹음");
        assertThat(request.rawRecordId()).isEqualTo(rawRecord.getId());
        assertThat(request.entryDate()).isEqualTo("2026-09-01");
        assertThat(request.roster()).containsExactly(
                new MatchingAgentRequest.RosterEntry(yujin.getId(), "임유진", "2019-11-26"));
        assertThat(request.hintName()).isEqualTo("임유진");
        assertThat(request.hintBirthdate()).isEqualTo("2019-11-26");
    }

    @Test
    void 표지_힌트가_비어_있으면_비운_채로_보낸다() {
        JournalEntry entry = entry(rawRecord(String.valueOf(ours.getId())));

        MatchingAgentRequest request = matchingService.prepareRequest(entry.getId());

        assertThat(request.hintName()).isNull();
        assertThat(request.hintBirthdate()).isNull();
    }

    @Test
    void 원본_파일이_삭제됐으면_요청을_만들_수_없다() {
        RawRecord rawRecord = rawRecord(String.valueOf(ours.getId()));
        JournalEntry entry = entry(rawRecord);
        rawRecord.delete();
        em.flush();

        assertThatThrownBy(() -> matchingService.prepareRequest(entry.getId()))
                .isInstanceOf(MatchingTargetException.class);
    }

    @Test
    void 원본의_기관이_없으면_요청을_만들_수_없다() {
        // 9/22 이전 행은 기관 ID 대신 카카오 회원번호가 들어 있다 (DB 스키마 §6.1). 숫자라 파싱은 된다.
        JournalEntry legacy = entry(rawRecord("3141592653"));
        JournalEntry broken = entry(rawRecord("kakao-abc"));

        assertThatThrownBy(() -> matchingService.prepareRequest(legacy.getId()))
                .isInstanceOf(MatchingTargetException.class);
        assertThatThrownBy(() -> matchingService.prepareRequest(broken.getId()))
                .isInstanceOf(MatchingTargetException.class);
    }

    @Test
    void 매칭_중에_멈춘_일지를_대기로_되돌린다() {
        RawRecord rawRecord = rawRecord(String.valueOf(ours.getId()));
        entry(rawRecord);
        entry(rawRecord);
        matchingService.claimPending(10);

        int released = matchingService.releaseStuck();
        em.flush();
        em.clear();

        assertThat(released).isEqualTo(2);
        assertThat(matchingService.claimPending(10)).hasSize(2);
    }

    @Test
    void 집어_갔던_일지를_대기로_돌려놓는다() {
        RawRecord rawRecord = rawRecord(String.valueOf(ours.getId()));
        JournalEntry claimed = entry(rawRecord);
        JournalEntry notClaimed = entry(rawRecord);
        matchingService.claimPending(1);

        matchingService.release(List.of(claimed.getId(), notClaimed.getId()));
        em.flush();
        em.clear();

        assertThat(em.find(JournalEntry.class, claimed.getId()).getStatus()).isEqualTo(JournalEntryStatus.PENDING);
        assertThat(em.find(JournalEntry.class, notClaimed.getId()).getStatus()).isEqualTo(JournalEntryStatus.PENDING);
    }
}
