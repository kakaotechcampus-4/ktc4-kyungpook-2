package com.itda.backend.service.organization;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.dto.response.organization.MyInstitutionResponse;
import com.itda.backend.exception.organization.OrganizationErrorCode;
import com.itda.backend.exception.organization.OrganizationException;
import com.itda.backend.global.exception.ConstraintViolations;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationCodeIssuer organizationCodeIssuer;
    private final UserService userService;

    /**
     * O-01 내 기관 정보. 기관 코드가 생기기 전에 가입한 기관은 처음 열 때 코드를 붙인다 — 조회지만 쓰기가 일어날 수
     * 있어 읽기 전용 트랜잭션이 아니다.
     *
     * <p>기관 행을 잠근 뒤 코드가 비어 있는지 본다. 잠그기 전에 읽어 두면 동시에 연 두 요청이 모두 "비어 있다" 고 보고
     * 서로 다른 코드를 붙인다.
     */
    @Transactional
    public MyInstitutionResponse getMine(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Organization organization = organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new IllegalStateException("소속 기관 행이 없습니다: " + organizationId));
        if (organization.getCode() == null) {
            organization.assignCode(organizationCodeIssuer.unusedCode());
            try {
                organizationRepository.saveAndFlush(organization);
            } catch (DataIntegrityViolationException e) {
                if (!ConstraintViolations.isViolationOf(e, Organization.UK_CODE)) {
                    throw e;
                }
                throw new OrganizationException(OrganizationErrorCode.INSTITUTION_CODE_ISSUE_FAILED);
            }
        }
        return MyInstitutionResponse.of(InstitutionResponse.from(organization), organization.getCode());
    }
}
