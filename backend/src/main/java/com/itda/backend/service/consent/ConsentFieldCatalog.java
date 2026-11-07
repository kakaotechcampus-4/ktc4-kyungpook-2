package com.itda.backend.service.consent;

import java.util.List;

import com.itda.backend.domain.consent.ConsentField;

/**
 * 동의할 때 받을 공유 항목 코드. 동의 미리보기(G-02)가 {@code allowedFields} 로 내려주고, 화면은 이 값을 동의(G-41)에
 * 그대로 돌려보낸다. 다른 곳에서 쓰지 않는다.
 */
final class ConsentFieldCatalog {

    /** 지금은 모든 기관 유형에 같은 항목을 연다. */
    static final List<ConsentField> ALLOWED_FIELDS = List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT);

    private ConsentFieldCatalog() {
    }
}
