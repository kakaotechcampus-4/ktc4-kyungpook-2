package com.itda.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    boolean existsByName(String name);

    /**
     * 기관 선택 화면이 없어서, 새로 로그인한 기관 담당자에게 붙일 기관을 서버가 정해야 한다.
     * 시연용으로 미리 넣어둔 기관 중 가장 먼저 만들어진 것을 쓴다.
     */
    Optional<Organization> findFirstByOrderByIdAsc();
}
