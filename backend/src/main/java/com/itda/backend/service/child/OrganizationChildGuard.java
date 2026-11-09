package com.itda.backend.service.child;

import org.springframework.stereotype.Component;

import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.user.UserService;

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

    /**
     * {@link #requireRosterChild} 와 같은 검사를 잠근 뒤에 한다. 잠금 순서는 child(공유) → child_organization(쓰기)다.
     *
     * <p>명부에 기대어 새 행을 만드는 쓰기(O-15 초대 코드 추가 발급)가 쓴다. 명부 제외는 child 를 쓰기 잠금으로
     * 먼저 잡으므로 둘이 줄을 선다 — 제외가 먼저 커밋되면 여기서 404 이고, 이쪽이 먼저면 제외가 그 결과까지 정리한다.
     * 이 검사끼리는 child 에서 서로 막지 않고 child_organization 행에서만 줄을 선다.
     *
     * @return 요청자의 기관 id
     */
    public Long lockRosterChild(String userId, Long childId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        boolean onRoster = childRepository.findByIdForShare(childId).filter(child -> !child.isDeleted()).isPresent()
                && childOrganizationRepository.findByChildIdAndOrganizationIdForUpdate(childId, organizationId)
                        .filter(link -> !link.isDeleted())
                        .isPresent();
        if (!onRoster) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_FOUND);
        }
        return organizationId;
    }
}
