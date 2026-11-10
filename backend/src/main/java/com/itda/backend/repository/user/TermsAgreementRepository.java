package com.itda.backend.repository.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.user.TermsAgreement;

public interface TermsAgreementRepository extends JpaRepository<TermsAgreement, Long> {

    List<TermsAgreement> findByUserId(Long userId);
}
