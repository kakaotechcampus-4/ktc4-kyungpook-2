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
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, "근거", "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingResult resolved = matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1");

        assertThat(resolved.getMatchedChildId()).isEqualTo(2L);
        assertThat(resolved.getStatus()).isEqualTo(MatchingStatus.AUTO);
        // 팀원 리뷰 반영: status만으론 AI 자동확정과 구분이 안 되니 reviewerId로 구분한다.
        assertThat(resolved.getReviewerId()).isEqualTo("kakao-teacher-1");
    }

    @Test
    void resolveAssignWithoutChildId_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, "근거", "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));

        assertThatThrownBy(() -> matchingResultService.resolve(1L, "assign", null, "kakao-teacher-1"))
                .isInstanceOf(MatchingResultValidationException.class);
    }

    @Test
    void resolveNotOurs_clearsMatchedChildAndLeavesQueue() {
        // 버그 재발 방지: 예전엔 여기서 status를 UNMATCHED로 뒀는데, UNMATCHED는
        // findByStatusNot(AUTO) 큐 조건에 여전히 걸려서 "제외" 처리해도 큐에서 안 빠졌다.
        MatchingResult matchingResult = new MatchingResult(
                1L, 5L, new BigDecimal("0.3"), MatchingStatus.MULTI, null, null, null, null, "근거", "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingResult resolved = matchingResultService.resolve(1L, "not_ours", null, "kakao-teacher-1");

        assertThat(resolved.getMatchedChildId()).isNull();
        assertThat(resolved.getStatus()).isEqualTo(MatchingStatus.AUTO);
        assertThat(resolved.getReviewerId()).isEqualTo("kakao-teacher-1");
    }

    @Test
    void aiAutoConfirmed_hasNoReviewerId() {
        // AI가 스스로 auto로 내놓은 건(사람 개입 없음) reviewerId가 비어있어야
        // 나중에 "누가 확정했나" 감사할 때 AI/사람을 구분할 수 있다.
        MatchingResult aiConfirmed = new MatchingResult(
                1L, 9L, new BigDecimal("0.97"), MatchingStatus.AUTO, null, null, null, null, null, "v1");

        assertThat(aiConfirmed.getReviewerId()).isNull();
    }

    @Test
    void resolveUnknownId_throwsNotFound() {
        given(matchingResultRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> matchingResultService.resolve(999L, "assign", 1L, "kakao-teacher-1"))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }
}
