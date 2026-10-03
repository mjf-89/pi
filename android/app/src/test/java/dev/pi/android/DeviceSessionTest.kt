package dev.pi.android

import org.junit.Assert.*
import org.junit.Test

class DeviceSessionTest {
    @Test fun authorizationsExpireAndAreNeverReusedAfterStop() {
        var time = 100L
        val session = DeviceSession { time }
        assertFalse(session.allows("model-invented-token"))
        val first = session.start("com.android.settings")
        assertEquals("com.android.settings", session.target)
        assertTrue(session.allows(first))
        assertFalse(session.consume("different-session"))
        time += 5 * 60_000
        assertFalse(session.consume(first))
        session.stop()
        assertNull(session.id)
        assertEquals("", session.target)
        val second = session.start("com.example.app")
        assertNotEquals(first, second)
        assertFalse(session.allows(first))
        assertTrue(session.allows(second))
        assertFalse(DeviceSession { time }.allows(second))
    }
    @Test fun operationBudgetAndSingleSessionLimitAreEnforced() {
        val session = DeviceSession { 0L }
        val id = session.start("com.android.settings")
        assertThrows(IllegalStateException::class.java) { session.start("com.example.other") }
        repeat(60) { assertTrue(session.consume(id)) }
        assertFalse(session.consume(id))
        session.stop()
        assertFalse(session.consume(id))
    }
}
