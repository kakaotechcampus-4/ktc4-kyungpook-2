package com.itda.backend.service.organization;

import org.springframework.stereotype.Component;

import com.itda.backend.exception.organization.OrganizationErrorCode;
import com.itda.backend.exception.organization.OrganizationException;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.invitation.InviteCodeGenerator;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrganizationCodeIssuer {

    private static final int MAX_CODE_ATTEMPTS = 5;

    private final OrganizationRepository organizationRepository;
    private final InviteCodeGenerator inviteCodeGenerator;

    public String unusedCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (!organizationRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new OrganizationException(OrganizationErrorCode.INSTITUTION_CODE_ISSUE_FAILED);
    }
}
