package com.itda.backend.repository.child;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.child.ChildCareInfo;

public interface ChildCareInfoRepository extends JpaRepository<ChildCareInfo, Long> {

    /** 저장할 때 쓴다. 아이 행을 잠근 뒤에 부른다 — 잠그기 전에 읽으면 첫 저장이 겹쳐 행이 둘 생길 수 있다. */
    Optional<ChildCareInfo> findByChildId(Long childId);

    /** 보호자 아이 목록·홈이 아이 여럿의 돌봄 정보를 한 번에 읽는다. */
    List<ChildCareInfo> findByChildIdIn(Collection<Long> childIds);
}
