package com.itda.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.ApprovalTargetType;
import com.itda.backend.domain.Approval;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    List<Approval> findByTargetTypeAndTargetId(ApprovalTargetType targetType, Long targetId);
}
