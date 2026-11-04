package com.itda.backend.repository.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.user.TermsAgreement;

public interface TermsAgreementRepository extends JpaRepository<TermsAgreement, Long> {

    /** 한 회원의 동의 기록 전부. 약관 둘 × 판 몇 개라 몇 줄 되지 않는다. */
    List<TermsAgreement> findByUserId(Long userId);
}
