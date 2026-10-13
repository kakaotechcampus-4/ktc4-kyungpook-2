package com.itda.backend.service;

import org.springframework.stereotype.Component;

import com.itda.backend.exception.ChildErrorCode;
import com.itda.backend.exception.ChildException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;

import lombok.RequiredArgsConstructor;

/**
 * 기관이 특정 아이를 다루는 API 의 권한 검사. 보안 설정은 로그인 여부만 보므로 역할·소속은 여기서 확인한다.
 *
 * <p>초대 코드·보호자 연결의 기관 쪽 메서드는 모두 맨 처음 이것을 부른다. {@code ChildService} 에 두지 않은 이유는
 * {@code ChildService} 가 초대 코드를 발급하려고 {@code InvitationService} 를 부르기 때문이다 — 반대 방향 의존이 생긴다.
 */
@Component
@RequiredArgsConstructor
public class OrganizationChildGuard {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;

    /**
     * 요청자가 기관 담당자이고(아니면 403 ORGANIZATION_NOT_ASSIGNED), 아이가 삭제되지 않았고, 그 기관 명부에
     * 지금 올라 있는지 확인한다. 뒤의 두 조건은 모두 404 CHILD_NOT_FOUND 다 — 다른 기관 아이가 있는지를 알려 주지 않는다.
     *
     * @return 요청자의 기관 id
     */
    public Long requireRosterChild(String userId, Long childId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (childRepository.findByIdAndDeletedAtIsNull(childId).isEmpty()
                || !childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_FOUND);
        }
        return organizationId;
    }
}
