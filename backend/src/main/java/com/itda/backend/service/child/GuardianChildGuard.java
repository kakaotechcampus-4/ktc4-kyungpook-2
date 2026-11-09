package com.itda.backend.service.child;

import org.springframework.stereotype.Component;

import com.itda.backend.domain.child.Child;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class GuardianChildGuard {

    private final ChildRepository childRepository;
    private final ChildGuardianRepository childGuardianRepository;

    public Child requireLinkedChild(Long parentId, Long childId) {
        if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
            throw notLinked();
        }
        return childRepository.findByIdAndDeletedAtIsNull(childId).orElseThrow(GuardianChildGuard::notLinked);
    }

    public Child lockLinkedChild(Long parentId, Long childId) {
        Child child = childRepository.findByIdForUpdate(childId).orElseThrow(GuardianChildGuard::notLinked);
        return requireAlive(child, parentId);
    }

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
