package com.itda.backend.service.organization;

import org.springframework.stereotype.Component;

import com.itda.backend.exception.organization.OrganizationErrorCode;
import com.itda.backend.exception.organization.OrganizationException;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.invitation.InviteCodeGenerator;

import lombok.RequiredArgsConstructor;

/**
 * 기관 코드를 뽑는다. 초대 코드와 같은 생성기를 쓴다 — 8자리, 헷갈리는 글자를 뺀 31자라 보호자가 옮겨 적기 쉽고
 * 오타로 엉뚱한 기관에 닿을 일이 거의 없다. 입력도 {@link InviteCodeGenerator#normalize} 로 같은 규칙을 따른다.
 *
 * <p>이미 쓰는 코드와 겹치지 않을 때까지 조회로 다시 뽑는다. 조회만 하므로 트랜잭션이 깨지지 않는다.
 * 그 사이 다른 요청이 같은 코드를 먼저 저장하는 경우는 저장하는 쪽이 유니크 제약 위반을 받아 503 으로 바꾼다.
 */
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
