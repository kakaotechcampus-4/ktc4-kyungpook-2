package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.repository.MatchingResultRepository;

@ExtendWith(MockitoExtension.class)
class MatchingResultServiceTest {

    @Mock
    private MatchingResultRepository matchingResultRepository;

    private MatchingResultService matchingResultService;

    @BeforeEach
    void setUp() {
        matchingResultService = new MatchingResultService(matchingResultRepository);
    }

    @Test
    void resolveAssign_setsMatchedChildAndAutoStatus() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, "근거", "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingResult resolved = matchingResultService.resolve(1L, "assign", 2L);

        assertThat(resolved.getMatchedChildId()).isEqualTo(2L);
        assertThat(resolved.getStatus()).isEqualTo(MatchingStatus.AUTO);
    }

    @Test
    void resolveAssignWithoutChildId_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, "근거", "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));

        assertThatThrownBy(() -> matchingResultService.resolve(1L, "assign", null))
                .isInstanceOf(MatchingResultValidationException.class);
    }

    @Test
    void resolveNotOurs_clearsMatchedChildAndSetsUnmatched() {
        MatchingResult matchingResult = new MatchingResult(
                1L, 5L, new BigDecimal("0.3"), MatchingStatus.MULTI, "근거", "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingResult resolved = matchingResultService.resolve(1L, "not_ours", null);

        assertThat(resolved.getMatchedChildId()).isNull();
        assertThat(resolved.getStatus()).isEqualTo(MatchingStatus.UNMATCHED);
    }

    @Test
    void resolveUnknownId_throwsNotFound() {
        given(matchingResultRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> matchingResultService.resolve(999L, "assign", 1L))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }
}
