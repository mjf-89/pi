package dev.pi.android

import org.junit.Assert.*
import org.junit.Test

class DeviceActionPolicyTest {
    @Test fun routineNavigationRunsAutomaticallyUnlessUserChoosesConfirmation() {
        assertFalse(DeviceActionPolicy.needsConfirmation(false, false, false, "About phone"))
        assertTrue(DeviceActionPolicy.needsConfirmation(true, false, false, "About phone"))
    }
    @Test fun consequentialActionsRemainReviewable() {
        assertTrue(DeviceActionPolicy.needsConfirmation(false, true, false, "Continue"))
        assertTrue(DeviceActionPolicy.needsConfirmation(false, false, false, "Send message"))
        assertTrue(DeviceActionPolicy.needsConfirmation(false, false, false, "app:id/delete_item"))
        assertTrue(DeviceActionPolicy.needsConfirmation(false, false, true, "Location"))
    }
}
