package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.exception.agent.AiAgentException;
import com.itda.backend.exception.agent.AiAgentRejectedException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.agent.AiAgentUnreadableResponseException;
import com.itda.backend.exception.validation.ValidationTargetException;

class AgentFailureReasonsTest {

    @Test
    void AI_가_거절한_요청은_REQUEST_REJECTED다() {
        assertThat(AgentFailureReasons.of(new AiAgentRejectedException("422", null)))
                .isEqualTo(FailureReason.REQUEST_REJECTED);
    }

    @Test
    void 읽을_수_없는_응답은_RESPONSE_UNREADABLE이다() {
        assertThat(AgentFailureReasons.of(new AiAgentUnreadableResponseException("empty body")))
                .isEqualTo(FailureReason.RESPONSE_UNREADABLE);
    }

    @Test
    void AI_다운은_AGENT_UNAVAILABLE이다() {
        assertThat(AgentFailureReasons.of(new AiAgentUnavailableException("down", null)))
                .isEqualTo(FailureReason.AGENT_UNAVAILABLE);
    }

    @Test
    void 그_밖의_예외는_BE_자체_오류로_본다() {
        assertThat(AgentFailureReasons.of(new ValidationTargetException("journal entry has no matched child")))
                .isEqualTo(FailureReason.INTERNAL_ERROR);
        assertThat(AgentFailureReasons.of(new IllegalStateException("db hiccup")))
                .isEqualTo(FailureReason.INTERNAL_ERROR);
        assertThat(AgentFailureReasons.of(new AiAgentException("interrupted while waiting to retry")))
                .isEqualTo(FailureReason.INTERNAL_ERROR);
    }
}
