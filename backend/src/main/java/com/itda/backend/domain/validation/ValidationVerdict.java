package com.itda.backend.domain.validation;

import com.itda.backend.domain.matching.MatchingStatus;

public enum ValidationVerdict {
    PASS,
    REVIEW,
    BLOCK,
    FAILED
}
