package com.itda.backend.service.child;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildCareInfo;
import com.itda.backend.domain.child.ChildCareInfo.Medication;
import com.itda.backend.dto.request.child.UpdateCareInfoRequest;
import com.itda.backend.dto.response.child.ChildCareInfoResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildCareInfoRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 돌봄 정보 저장 (api-spec G-13). <b>현재 이 아이와 연결된 모든 보호자가 조회·수정할 수 있고, 기관에는 공유하지
 * 않는다.</b> 아이마다 한 행이고 나중에 저장한 쪽이 덮어쓴다. 조회는 보호자 아이 목록·홈이 싣는다
 * ({@link GuardianChildQueryService}).
 *
 * <p>아이 행을 잠근 뒤에 연결·삭제를 보고 저장한다 (DB 스키마 §3.3). 첫 저장이 겹쳐도 행이 둘 생기지 않고, 연결 해제와
 * 줄을 서서 해제가 먼저 반영되면 403 이고 돌봄 정보는 생기지도 바뀌지도 않는다. 잠그기 전에 아이·연결·돌봄 정보를
 * 엔티티로 읽지 않는다 — 영속성 컨텍스트가 잠그기 전 값을 돌려줘 그사이 커밋된 변경을 놓친다.
 *
 * <p>입력 정리(앞뒤 공백·중복)와 검사는 요청에서 끝난다({@link UpdateCareInfoRequest}). 틀린 요청은 아이 행을 잡지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChildCareInfoService {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final ChildCareInfoRepository childCareInfoRepository;

    @Transactional
    public ChildCareInfoResponse save(String userId, Long childId, UpdateCareInfoRequest request) {
        Long parentId = userService.getParentIdOf(userId);
        Child child = childRepository.findByIdForUpdate(childId)
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_LINKED));
        if (child.isDeleted()
                || !childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_LINKED);
        }

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
