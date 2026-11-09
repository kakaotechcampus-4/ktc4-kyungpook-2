package com.itda.backend.service.child;

import org.springframework.stereotype.Component;

import com.itda.backend.domain.child.Child;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;

import lombok.RequiredArgsConstructor;

/**
 * 보호자가 특정 아이를 다루는 API 의 권한 검사. 이 보호자와 연결돼 있고 삭제되지 않은 아이여야 한다. 아니면 이유를 나누지 않고
 * 403 CHILD_NOT_LINKED 다 — 보호자 홈과 같은 기준이다. 기관 쪽은 {@link OrganizationChildGuard} 다.
 *
 * <p>잠금이 필요한 쓰기는 {@code lock…} 을 쓴다. 아이 행을 먼저 잠그고 그다음 연결을 본다 — 연결 해제 · 명부 제외가 아이 행을
 * 쓰기 잠금으로 먼저 잡으므로 이 검사와 줄을 선다. 해제가 먼저 커밋되면 여기서 403 이다.
 */
@Component
@RequiredArgsConstructor
public class GuardianChildGuard {

    private final ChildRepository childRepository;
    private final ChildGuardianRepository childGuardianRepository;

    /** 읽기용 — 잠그지 않는다. */
    public Child requireLinkedChild(Long parentId, Long childId) {
        if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
            throw notLinked();
        }
        return childRepository.findByIdAndDeletedAtIsNull(childId).orElseThrow(GuardianChildGuard::notLinked);
    }

    /** 아이 행을 쓰기 잠금으로 잡은 뒤 본다. */
    public Child lockLinkedChild(Long parentId, Long childId) {
        Child child = childRepository.findByIdForUpdate(childId).orElseThrow(GuardianChildGuard::notLinked);
        return requireAlive(child, parentId);
    }

    /** 아이 행을 공유 잠금으로 잡은 뒤 본다. 같은 아이를 공유 잠금으로 보는 쓰기끼리는 서로 막지 않는다. */
    public Child lockLinkedChildForShare(Long parentId, Long childId) {
        Child child = childRepository.findByIdForShare(childId).orElseThrow(GuardianChildGuard::notLinked);
        return requireAlive(child, parentId);
    }

    private Child requireAlive(Child child, Long parentId) {
        if (child.isDeleted()
                || !childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(child.getId(), parentId)) {
            throw notLinked();
        }
        return child;
    }

    private static ChildException notLinked() {
        return new ChildException(ChildErrorCode.CHILD_NOT_LINKED);
    }
}
