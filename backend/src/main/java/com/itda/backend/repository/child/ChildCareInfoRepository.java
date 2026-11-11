package com.itda.backend.repository.child;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.child.ChildCareInfo;

public interface ChildCareInfoRepository extends JpaRepository<ChildCareInfo, Long> {

    Optional<ChildCareInfo> findByChildId(Long childId);

    List<ChildCareInfo> findByChildIdIn(Collection<Long> childIds);
}
