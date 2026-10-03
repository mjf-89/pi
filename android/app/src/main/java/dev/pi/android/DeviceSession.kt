package dev.pi.android

import java.util.UUID

/** Ephemeral authorization: never persisted or restored with a conversation. */
class DeviceSession(private val now: () -> Long) {
    var id: String? = null
        private set
    var target: String = ""
        private set
    private var deadline = 0L
    private var operations = 0
    fun start(packageName: String): String {
        check(id == null) { "A device task is already running" }
        target = packageName
        deadline = now() + 5 * 60_000
        operations = 0
        return UUID.randomUUID().toString().also { id = it }
    }
    fun allows(session: String): Boolean = session == id && now() < deadline && operations < 60
    fun consume(session: String): Boolean {
        if (!allows(session)) return false
        operations++
        return true
    }
    fun stop() { id = null; target = "" }
}
