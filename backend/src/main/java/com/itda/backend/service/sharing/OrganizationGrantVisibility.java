package com.itda.backend.service.sharing;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.sharing.SharingHistory;

/**
 * 받는 기관이 인사이트를 <b>지금</b> 읽어도 되는지. 기관 수신함(O-50 · O-51)과 보호자의 기관 공유 내역(G-45)이 같이 쓴다 —
 * 갈리면 보호자 화면은 "볼 수 있음" 인데 기관 수신함은 숨기는 일이 생긴다.
 */
final class OrganizationGrantVisibility {

    private OrganizationGrantVisibility() {
    }

    /**
     * 넷 다 참이어야 한다.
     *
     * <ol>
     * <li>권한 줄이 거둬지지 않았다</li>
     * <li>받는 기관 명부에 그 아이가 지금 있다 — 권한은 만들 때 굳지만 명부는 바뀐다</li>
     * <li>받는 기관 기준 동의가 켜져 있다 — 다른 기관 동의로 켜진 아이 전체 상태가 아니다</li>
     * <li>아이가 삭제되지 않았다</li>
     * </ol>
     */
    static boolean readable(SharingHistory grant, Child child, ChildOrganization enrollment, ConsentState consent) {
        return grant.isActive()
                && child != null && !child.isDeleted()
                && enrollment != null && !enrollment.isDeleted()
                && consent == ConsentState.GRANTED;
    }
}
