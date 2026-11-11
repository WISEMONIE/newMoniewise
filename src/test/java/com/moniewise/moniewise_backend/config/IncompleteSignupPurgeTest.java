package com.moniewise.moniewise_backend.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncompleteSignupPurgeTest {

    @Test
    void purgeIdsLeaveOutThePlayStoreReviewAccount() {
        List<Long> incoming = new ArrayList<>();
        incoming.add(71L);
        incoming.add(80L);
        incoming.add(71L);
        incoming.add(null);
        incoming.add(90L);

        List<Long> ids = IncompleteSignupLifecycleManager.idsEligibleForPurge(incoming);

        assertEquals(List.of(80L, 90L), ids);
        assertFalse(ids.contains(IncompleteSignupLifecycleManager.PLAY_STORE_REVIEW_USER_ID));
    }

    @Test
    void aBatchThatIsOnlyTheReviewAccountPurgesNobody() {
        assertTrue(IncompleteSignupLifecycleManager.idsEligibleForPurge(List.of(71L)).isEmpty());
        assertTrue(IncompleteSignupLifecycleManager.idsEligibleForPurge(null).isEmpty());
    }
}
