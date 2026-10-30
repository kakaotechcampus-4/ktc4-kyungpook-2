package com.itda.backend.service.insight;

import java.util.Collection;
import java.util.List;

/**
 * Gate 2 승인으로 이 글을 받을 기관 후보 (#233). <b>동의는 부르는 쪽이 본다</b> — 화면은 동의 상태를 함께
 * 보여주고, 승인은 동의가 켜진 곳에만 연다.
 *
 * <p>화면({@link InsightGate2QueryService})과 승인({@link InsightGate2Service})이 이 한 곳을 쓴다. 갈리면
 * 화면에 보인 기관과 실제로 받는 기관이 달라진다.
 */
final class Gate2Recipients {

    private Gate2Recipients() {
    }

    /**
     * 그 아이 명부에 있는 기관 중 <b>승인하는 기관 자신만</b> 뺀다 — 자기 글을 자기에게 열지 않는다.
     *
     * <p>재료를 낸 다른 기관은 받는다. Gate 2 목록은 승인 기관에게만 보이므로, 여기서 빼면 그 기관은 자기
     * 기록이 섞인 기관 간 인사이트를 영영 못 본다.
     *
     * @param enrolled 그 아이가 지금 다니는 기관 id
     */
    static List<Long> candidatesOf(Collection<Long> enrolled, Long approverId) {
        return enrolled.stream()
                .filter(id -> !id.equals(approverId))
                .distinct()
                .sorted()
                .toList();
    }
}
