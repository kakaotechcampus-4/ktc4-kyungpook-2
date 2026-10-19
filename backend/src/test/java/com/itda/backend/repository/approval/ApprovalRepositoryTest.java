package com.itda.backend.repository.approval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalReviewType;
import com.itda.backend.domain.approval.ApprovalTargetType;

@DataJpaTest
@ActiveProfiles("test")
class ApprovalRepositoryTest {

    @Autowired
    private ApprovalRepository approvalRepository;

    @Test
    void savesAndLoadsByPolymorphicTarget() {
        Approval approval = new Approval(
                ApprovalTargetType.SUMMARY, 10L, ApprovalReviewType.GATE1, 99L,
                ApprovalDecision.APPROVED, "확인함");

        approvalRepository.save(approval);

        var found = approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, 10L);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getReviewType()).isEqualTo(ApprovalReviewType.GATE1);
        assertThat(found.get(0).getDecision()).isEqualTo(ApprovalDecision.APPROVED);
    }
}
