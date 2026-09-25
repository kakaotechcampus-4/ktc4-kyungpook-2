package com.itda.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** 기관 1곳당 계정 1개 — 같은 사업자등록번호로 두 번 가입하지 못하게 먼저 확인한다. */
    boolean existsByBusinessNumber(String businessNumber);
}
