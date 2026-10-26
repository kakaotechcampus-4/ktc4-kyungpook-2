package com.itda.backend.service.consent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.repository.consent.SharingConsentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 아이·기관별 동의 상태를 {@code sharing_consent} 행에서 계산한다 (DB 스키마 §5.5). 상태는 아이 기준이다 —
 * 어느 보호자의 동의든 하나라도 살아 있으면 그 기관 공유는 켜져 있다.
 *
 * <pre>
 * GRANTED      그 기관에 ACTIVE 동의가 하나라도 있다
 * REVOKED      동의 행은 있지만 모두 REVOKED 다
 * NOT_GRANTED  그 기관에 동의 행이 없다
 * </pre>
 *
 * <p>동의 서비스, Gate 2 공유 대상, 보호자 아이 목록이 모두 이 클래스를 쓴다. 같은 계산을 다른 곳에 다시 만들지
 * 않는다 — 한쪽만 고치면 기관 화면과 보호자 화면의 상태가 어긋난다.
 *
 * <p>트랜잭션을 따로 열지 않고 호출하는 쪽 것을 쓴다. 같은 트랜잭션에서 바꾼 동의 행은 조회 전에 Hibernate 가
 * 먼저 내보낸다(같은 테이블 JPQL 이라 자동 flush).
 */
@Service
@RequiredArgsConstructor
public class ConsentStateReader {

    private final SharingConsentRepository sharingConsentRepository;

    public ConsentState stateOf(Long childId, Long organizationId) {
        if (sharingConsentRepository.existsByChildIdAndOrganizationIdAndStatus(
                childId, organizationId, SharingConsentStatus.ACTIVE)) {
            return ConsentState.GRANTED;
        }
        return sharingConsentRepository.existsByChildIdAndOrganizationId(childId, organizationId)
                ? ConsentState.REVOKED
                : ConsentState.NOT_GRANTED;
    }

    /**
     * 여러 아이의 기관별 상태를 쿼리 한 번으로. 결과에 없는 (아이, 기관) 은 {@link ConsentState#NOT_GRANTED} 다 —
     * 꺼내는 쪽은 {@code getOrDefault(organizationId, NOT_GRANTED)} 로 읽는다.
     */
    public Map<Long, Map<Long, ConsentState>> statesOf(Collection<Long> childIds) {
        Map<Long, Map<Long, ConsentState>> states = new HashMap<>();
        if (childIds.isEmpty()) {
            return states;
        }
        for (SharingConsent consent : sharingConsentRepository.findByChildIdIn(childIds)) {
            Map<Long, ConsentState> byOrganization =
                    states.computeIfAbsent(consent.getChildId(), k -> new HashMap<>());
            ConsentState current = byOrganization.get(consent.getOrganizationId());
            if (current == ConsentState.GRANTED) {
                continue;
            }
            byOrganization.put(consent.getOrganizationId(),
                    consent.isActive() ? ConsentState.GRANTED : ConsentState.REVOKED);
        }
        return states;
    }
}
