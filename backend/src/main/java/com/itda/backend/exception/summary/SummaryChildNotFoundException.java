package com.itda.backend.exception.summary;

import com.itda.backend.global.exception.BusinessException;

/** 요청한 아동이 없거나 다른 기관 소속이다. 다른 기관 아이도 "권한 없음"이 아니라 "없음"으로 응답한다. */
public class SummaryChildNotFoundException extends BusinessException {

    public SummaryChildNotFoundException(Long childId) {
        super(SummaryErrorCode.CHILD_NOT_FOUND, "child not found in institution: " + childId);
    }
}
