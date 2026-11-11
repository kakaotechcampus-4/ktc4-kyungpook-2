package com.itda.backend.service.consent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.repository.consent.SharingConsentRepository;

import lombok.RequiredArgsConstructor;

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

    public Map<Long, Map<Long, ConsentState>> statesOf(Collection<Long> childIds) {
        return statesOf(childIds, SharingConsent::isActive);
    }

    public Set<Long> grantedChildIds(Collection<Long> childIds, Long organizationId) {
        Map<Long, Map<Long, ConsentState>> states = statesOf(childIds);
        return childIds.stream()
                .filter(childId -> states.getOrDefault(childId, Map.of())
                        .getOrDefault(organizationId, ConsentState.NOT_GRANTED) == ConsentState.GRANTED)
                .collect(Collectors.toSet());
    }

    public ConsentState insightStateOf(Long childId, Long organizationId) {
        return sharingConsentRepository.findByChildIdAndOrganizationId(childId, organizationId).stream()
                .map(consent -> classify(consent, INSIGHT))
                .reduce(ConsentStateReader::stronger)
                .orElse(ConsentState.NOT_GRANTED);
    }

    public Map<Long, Map<Long, ConsentState>> insightStatesOf(Collection<Long> childIds) {
        return statesOf(childIds, INSIGHT);
    }

    private static final Predicate<SharingConsent> INSIGHT = consent -> consent.allows(ConsentField.WEEKLY_INSIGHT);

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
