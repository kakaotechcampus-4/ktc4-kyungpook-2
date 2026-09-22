package com.itda.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.repository.MatchingResultRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchingResultService {

    private final MatchingResultRepository matchingResultRepository;

    public List<MatchingResult> getQueue() {
        return matchingResultRepository.findByStatusNot(MatchingStatus.AUTO);
    }

    public MatchingResult resolve(Long id, String action, Long childId) {
        MatchingResult matchingResult = matchingResultRepository.findById(id)
                .orElseThrow(() -> new MatchingResultNotFoundException(id));

        switch (action == null ? "" : action) {
            case "assign" -> {
                if (childId == null) {
                    throw new MatchingResultValidationException("childId is required for assign");
                }
                matchingResult.resolveAsAssigned(childId);
            }
            case "not_ours" -> matchingResult.resolveAsNotOurs();
            default -> throw new MatchingResultValidationException("unsupported action: " + action);
        }

        return matchingResultRepository.save(matchingResult);
    }
}
