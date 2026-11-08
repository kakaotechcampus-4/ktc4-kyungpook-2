package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잠그는 검사는 부르는 쪽 트랜잭션 안에서만 부른다 (#424) — 검사와 일지 반영이 한 트랜잭션이어야 동시 요청이 함께 통과하지
 * 못한다. 업로드는 저장소에 올리기 전에 트랜잭션 밖에서 미리 보므로, 미리 보기까지 막으면 업로드가 전부 실패한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(AgentQueueGuardTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentQueueGuardTransactionTest {

    @Autowired
    private AgentQueueGuard guard;

    @Test
    void 잠그는_검사는_트랜잭션_밖에서_부를_수_없다() {
        assertThatThrownBy(() -> guard.requireRoom(1L, 1)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 미리_보기는_트랜잭션_밖에서_부를_수_있다() {
        assertThatCode(() -> guard.peek(1L, 1)).doesNotThrowAnyException();
    }
}
