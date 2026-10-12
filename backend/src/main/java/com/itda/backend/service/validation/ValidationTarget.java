package com.itda.backend.service.validation;

import com.itda.backend.dto.request.ValidationAgentRequest;

/**
 * 검증 한 건의 입력. 요청과 함께 그 요청을 만든 매칭 결과를 들고 다녀서 결과를 저장할 때 다시 조회하지 않는다.
 *
 * @param matchingResultId 입력으로 쓴 매칭 결과. 없으면 null (validation_result.matching_result_id 는 NULL 허용)
 */
public record ValidationTarget(ValidationAgentRequest request, Long matchingResultId) {
}
