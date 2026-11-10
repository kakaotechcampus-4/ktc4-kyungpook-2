package com.itda.backend.service.consent;

import java.util.List;

import com.itda.backend.domain.consent.ConsentField;

final class ConsentFieldCatalog {

    static final List<ConsentField> ALLOWED_FIELDS = List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT);

    private ConsentFieldCatalog() {
    }
}
