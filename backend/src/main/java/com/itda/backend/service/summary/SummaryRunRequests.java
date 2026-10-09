package com.itda.backend.service.summary;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 교사가 누른 "지금 요약" 요청. 워커가 그 묶음을 마감·디바운스 없이 집어 가면 지운다.
 *
 * <p>요청마다 만료 시각이 있다. 그 시각이 지나면 어차피 정해진 규칙(마감·디바운스)대로 요약되므로 요청이 더는
 * 필요 없다. 만료가 없으면 요약할 게 없어 남은 요청 때문에, 한참 뒤 그 날짜로 올라온 일지가 디바운스 없이 요약된다.
 *
 * <p>메모리에만 둔다 — 앱이 재시작되면 사라지고, 그때는 교사가 다시 누르거나 마감에 요약된다.
 * 서버가 한 대라 이걸로 충분하다.
 */
@Component
public class SummaryRunRequests {

    private final Map<SummaryGroup, Instant> requested = new ConcurrentHashMap<>();

    public void request(SummaryGroup group, Instant expiresAt) {
        requested.put(group, expiresAt);
    }

    /** 아직 유효한 요청이 있는지. 만료된 요청은 이때 지운다. */
    public boolean isRequested(SummaryGroup group, Instant now) {
        Instant expiresAt = requested.get(group);
        if (expiresAt == null) {
            return false;
        }
        if (!now.isBefore(expiresAt)) {
            requested.remove(group, expiresAt);
            return false;
        }
        return true;
    }

    public void remove(SummaryGroup group) {
        requested.remove(group);
    }
}
