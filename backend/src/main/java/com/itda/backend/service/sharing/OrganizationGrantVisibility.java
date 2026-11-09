package com.itda.backend.service.sharing;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.sharing.SharingHistory;

final class OrganizationGrantVisibility {

    private OrganizationGrantVisibility() {
    }

    static boolean readable(SharingHistory grant, Child child, ChildOrganization enrollment, ConsentState consent) {
        return grant.isActive()
                && child != null && !child.isDeleted()
                && enrollment != null && !enrollment.isDeleted()
                && consent == ConsentState.GRANTED;
    }
}
