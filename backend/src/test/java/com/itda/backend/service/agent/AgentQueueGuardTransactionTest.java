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
