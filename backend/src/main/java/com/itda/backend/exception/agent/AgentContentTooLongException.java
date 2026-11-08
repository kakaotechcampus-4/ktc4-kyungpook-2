package com.itda.backend.exception.agent;

import com.itda.backend.domain.record.JournalEntry;

/**
 * 일지 본문이 AI 에 보낼 수 있는 길이({@link JournalEntry#MAX_CONTENT_LENGTH})를 넘어 요청을 만들지 않았다 (#424). 각 에이전트의
 * {@code prepareRequest} 가 상태를 바꾸지 않고 던지기만 한다 — 상태는 워커가 받아 쓰기 트랜잭션 레코더로 남긴다
 * ({@code FailureReason.CONTENT_TOO_LONG}). AI 를 부르지 않았으므로 {@link AiAgentException} 의 하위 타입이 아니다.
 */
public class AgentContentTooLongException extends RuntimeException {

    public AgentContentTooLongException(Long journalEntryId, int length) {
        super("journal entry content too long to send to agent journalEntryId=" + journalEntryId
                + " length=" + length + " max=" + JournalEntry.MAX_CONTENT_LENGTH);
    }
}
