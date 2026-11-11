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

    @Transactional
    public MyInstitutionResponse getMine(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Organization organization = organizationRepository.lockOwnOrganization(organizationId);
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
