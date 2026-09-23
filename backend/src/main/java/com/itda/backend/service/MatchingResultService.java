package com.itda.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.repository.MatchingResultRepository;

import lombok.RequiredArgsConstructor;

// backend/AGENTS.md: 조회는 @Transactional(readOnly = true), 변경은 @Transactional로 범위를 명시한다.
@Service
@RequiredArgsConstructor
public class MatchingResultService {

    private final MatchingResultRepository matchingResultRepository;

    @Transactional(readOnly = true)
    public List<MatchingResult> getQueue() {
        return matchingResultRepository.findByStatusNot(MatchingStatus.AUTO);
    }

    @Transactional
    public MatchingResult resolve(Long id, String action, Long childId, String reviewerId) {
        MatchingResult matchingResult = matchingResultRepository.findById(id)
                .orElseThrow(() -> new MatchingResultNotFoundException(id));

        switch (action == null ? "" : action) {
            case "assign" -> {
                if (childId == null) {
                    throw new MatchingResultValidationException("childId is required for assign");
                }
                matchingResult.resolveAsAssigned(childId, reviewerId);
            }
            case "not_ours" -> matchingResult.resolveAsNotOurs(reviewerId);
            default -> throw new MatchingResultValidationException("unsupported action: " + action);
        }

        return matchingResultRepository.save(matchingResult);
    }
}
