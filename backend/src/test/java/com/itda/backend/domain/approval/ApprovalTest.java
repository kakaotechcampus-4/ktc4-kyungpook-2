package com.itda.backend.domain.approval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApprovalTest {

    @Test
    void Gate1_결정은_요약을_가리키고_사유는_비운다() {
        Approval approval = Approval.gate1(10L, 99L, ApprovalDecision.HOLD);

        assertThat(approval.getTargetType()).isEqualTo(ApprovalTargetType.SUMMARY);
        assertThat(approval.getTargetId()).isEqualTo(10L);
        assertThat(approval.getReviewType()).isEqualTo(ApprovalReviewType.GATE1);
        assertThat(approval.getReviewerId()).isEqualTo(99L);
        assertThat(approval.getDecision()).isEqualTo(ApprovalDecision.HOLD);
        assertThat(approval.getComment()).isNull();
        assertThat(approval.getReviewedAt()).isNotNull();
    }
}
