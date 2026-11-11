package com.itda.backend.service.child;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildCareInfo;
import com.itda.backend.domain.child.ChildCareInfo.Medication;
import com.itda.backend.dto.request.child.UpdateCareInfoRequest;
import com.itda.backend.dto.response.child.ChildCareInfoResponse;
import com.itda.backend.repository.child.ChildCareInfoRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChildCareInfoService {

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildCareInfoRepository childCareInfoRepository;

    @Transactional
    public ChildCareInfoResponse save(String userId, Long childId, UpdateCareInfoRequest request) {
        Long parentId = userService.getParentIdOf(userId);
        Child child = guardianChildGuard.lockLinkedChild(parentId, childId);

        List<String> allergies = request.allergies();
        List<Medication> medications = request.medications().stream()
                .map(medication -> new Medication(medication.name(), medication.time()))
                .toList();
        ChildCareInfo info = childCareInfoRepository.findByChildId(childId)
                .map(existing -> {
                    existing.replace(allergies, medications, parentId);
                    return existing;
                })
                .orElseGet(() -> childCareInfoRepository.save(
                        ChildCareInfo.of(childId, allergies, medications, parentId)));
        return ChildCareInfoResponse.from(info);
    }
}
