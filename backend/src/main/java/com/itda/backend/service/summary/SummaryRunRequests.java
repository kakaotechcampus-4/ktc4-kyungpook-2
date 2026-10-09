package com.itda.backend.service.summary;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 교사가 누른 "지금 요약" 요청. 워커가 그 묶음을 마감·디바운스 없이 집어 가면 지운다.
 *
 * <p>메모리에만 둔다 — 앱이 재시작되면 사라지고, 그때는 교사가 다시 누르거나 다음 날 마감에 요약된다.
 * 서버가 한 대라 이걸로 충분하다.
 */
@Component
public class SummaryRunRequests {

    private final Set<SummaryGroup> requested = ConcurrentHashMap.newKeySet();

    public void request(SummaryGroup group) {
        requested.add(group);
    }

    public boolean contains(SummaryGroup group) {
        return requested.contains(group);
    }

    public void remove(SummaryGroup group) {
        requested.remove(group);
    }
}
