package com.itda.backend.service.consent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import com.itda.backend.domain.consent.ConsentField;
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
        return statesOf(childIds, SharingConsent::isActive);
    }

    /**
     * <b>주간 인사이트</b> 공유 동의 상태 (#257). 인사이트 재료 고르기 · Gate 2 승인과 받는 기관 · Gate 2 화면이 모두
     * 이 값을 쓴다 — 하루 요약만 허락한 기관에 주간 인사이트가 열리면 안 된다 (멘토님 리뷰 #256 P1).
     *
     * <pre>
     * GRANTED      살아 있는 동의 중 하나라도 WEEKLY_INSIGHT 를 담고 있다
     * NOT_GRANTED  동의 행이 없거나, 살아 있어도 하루 요약만 허락했다
     * REVOKED      동의 행은 있지만 모두 꺼졌다
     * </pre>
     */
    public ConsentState insightStateOf(Long childId, Long organizationId) {
        return sharingConsentRepository.findByChildIdAndOrganizationId(childId, organizationId).stream()
                .map(consent -> classify(consent, INSIGHT))
                .reduce(ConsentStateReader::stronger)
                .orElse(ConsentState.NOT_GRANTED);
    }

    /** {@link #insightStateOf} 를 여러 아이에 쿼리 한 번으로. 결과에 없는 (아이, 기관) 은 NOT_GRANTED 다. */
    public Map<Long, Map<Long, ConsentState>> insightStatesOf(Collection<Long> childIds) {
        return statesOf(childIds, INSIGHT);
    }

    private static final Predicate<SharingConsent> INSIGHT = consent -> consent.allows(ConsentField.WEEKLY_INSIGHT);

    /**
     * 동의 행을 (아이, 기관) 마다 하나의 상태로 접는다. 같은 기관에 보호자별 행이 여럿일 수 있어
     * 허락 &gt; 살아 있음 &gt; 꺼짐 순으로 가장 센 것을 남긴다.
     *
     * @param grants 이 행을 "허락" 으로 볼지. 공유 자체는 {@code isActive}, 인사이트는 {@code WEEKLY_INSIGHT} 까지 본다
     */
    private Map<Long, Map<Long, ConsentState>> statesOf(Collection<Long> childIds, Predicate<SharingConsent> grants) {
        Map<Long, Map<Long, ConsentState>> states = new HashMap<>();
        if (childIds.isEmpty()) {
            return states;
        }
        for (SharingConsent consent : sharingConsentRepository.findByChildIdIn(childIds)) {
            states.computeIfAbsent(consent.getChildId(), k -> new HashMap<>())
                    .merge(consent.getOrganizationId(), classify(consent, grants), ConsentStateReader::stronger);
        }
        return states;
    }

    private static ConsentState classify(SharingConsent consent, Predicate<SharingConsent> grants) {
        if (grants.test(consent)) {
            return ConsentState.GRANTED;
        }
        return consent.isActive() ? ConsentState.NOT_GRANTED : ConsentState.REVOKED;
    }

    private static ConsentState stronger(ConsentState a, ConsentState b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(ConsentState state) {
        return switch (state) {
            case GRANTED -> 2;
            case NOT_GRANTED -> 1;
            case REVOKED -> 0;
        };
    }
}
