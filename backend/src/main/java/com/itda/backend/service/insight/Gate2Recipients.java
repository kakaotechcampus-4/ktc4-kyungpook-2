package com.itda.backend.service.insight;

import java.util.Collection;
import java.util.List;

final class Gate2Recipients {

    private Gate2Recipients() {
    }

    static List<Long> candidatesOf(Collection<Long> enrolled, Long approverId) {
        return enrolled.stream()
                .filter(id -> !id.equals(approverId))
                .distinct()
                .sorted()
                .toList();
    }
}
