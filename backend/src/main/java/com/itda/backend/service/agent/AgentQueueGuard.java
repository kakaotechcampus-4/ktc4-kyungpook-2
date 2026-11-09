package com.itda.backend.service.agent;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;

import lombok.RequiredArgsConstructor;

/**
 * 기관마다 AI 처리 줄에 세워 둘 수 있는 일지 수를 지킨다 (#424). 파일 하나로 일지를 수천 건 만들어 LLM 을 계속 부르게 하거나,
 * 다른 기관 기록을 그 뒤로 오래 밀어내는 일을 막는다.
 *
 * <p><b>지키는 범위는 사람이 일으키는 새 유입이다.</b> 업로드와, 사람 대기에서 AI 대기로 다시 들어오는 네 경로(확인 필요 큐의
 * 아이 고르기 · 막힌 본문 고치기 · 요약 실패 [다시 요약] · Gate 1 "요약할 내용 없음" 되돌리기)가 들어오기 직전에 부른다.
 * 요약 워커가 새 일지가 든 묶음을 집으며 그 묶음의 승인 대기(GATE1_PENDING) 일지를 함께 다시 담는 것은 막지 않는다 —
 * 막으면 줄이 꽉 찼을 때 워커가 묶음을 계속 건너뛰어 줄이 영영 줄지 않는다. 요약은 묶음마다 한 번 부르므로 함께 담긴 일지가
 * 호출 수를 늘리지도 않는다. 그래서 줄은 그 기관의 승인 대기 일지 수만큼 잠깐 상한을 넘을 수 있다.
 *
 * <p><b>잠금 순서</b> — 기관 행은 항상 마지막에 잠근다. 부르는 쪽은 아동·일지·요약 행을 먼저 잠근 뒤 여기 온다. 기관 행을
 * 먼저 잡고 다른 행을 기다리는 곳이 없으므로 교착이 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AgentQueueGuard {

    /** AI 를 기다리거나 AI 가 처리 중인 상태. 사람을 기다리는 상태(확인 필요·수정 요청·Gate 1)는 세지 않는다. */
    public static final Set<JournalEntryStatus> AWAITING_AGENT = EnumSet.of(
            JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING,
            JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING,
            JournalEntryStatus.VALIDATED, JournalEntryStatus.SUMMARIZING);

    private final OrganizationRepository organizationRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AgentQueueProperties properties;

    /**
     * 기관 행을 잠근 뒤 다시 세고, 자리가 없으면 던진다. 같은 기관의 동시 요청은 여기서 한 줄로 선다 — 앞 요청이 커밋한 일지까지
     * 세므로 둘이 함께 통과하지 못한다. 그래서 부르는 쪽 트랜잭션 안에서만 부른다: 이 검사와 일지 반영이 한 트랜잭션이어야 한다.
     *
     * @param incoming 새로 AI 처리 줄에 들어올 일지 수. 0 이면 잠그지도 세지도 않는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoom(Long organizationId, int incoming) {
        if (incoming <= 0) {
            return;
        }
        organizationRepository.lockOwnOrganization(organizationId);
        check(organizationId, incoming);
    }

    /**
     * 잠그지 않고 같은 셈을 한다. 업로드가 파일을 저장소에 올리기 전에 흔한 경우를 빨리 거절하려고 쓴다 — 상한을 지키는 것은
     * {@link #requireRoom} 의 다시 세기다. 업로드는 트랜잭션 밖에서 부르므로 MANDATORY 가 아니다.
     */
    @Transactional(readOnly = true)
    public void peek(Long organizationId, int incoming) {
        if (incoming <= 0) {
            return;
        }
        check(organizationId, incoming);
    }

    private void check(Long organizationId, int incoming) {
        long awaiting = journalEntryRepository.countByInstitutionAndStatusIn(
                String.valueOf(organizationId), AWAITING_AGENT);
        if (awaiting + incoming > properties.maxAwaitingEntries()) {
            throw new AgentQueueFullException(organizationId, awaiting, incoming, properties.maxAwaitingEntries());
        }
    }
}
