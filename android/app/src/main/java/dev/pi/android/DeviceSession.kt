package dev.pi.android

import java.util.UUID

/** Ephemeral authorization: never persisted or restored with a conversation. */
class DeviceSession(private val now: () -> Long) {
    var id: String? = null
        private set
    var target: String = ""
        private set
    var automaticApps = false
        private set
    var confirmActions = true
        private set
    private var allowedApps: Set<String> = emptySet()
    private var deadline = 0L
    private var operations = 0
    fun start(packageName: String, automatic: Boolean = false, confirm: Boolean = true, apps: Set<String> = setOf(packageName)): String {
        check(id == null) { "A device task is already running" }
        check(automatic || packageName in apps) { "Selected app is not available" }
        check(apps.isNotEmpty() && "" !in apps) { "No available apps" }
        target = packageName
        automaticApps = automatic
        confirmActions = confirm
        allowedApps = apps.toSet()
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
    fun selectApp(packageName: String) {
        check(id != null && now() < deadline) { "Device task is inactive" }
        check(packageName in allowedApps && (automaticApps || packageName == target)) { "App is outside this task's scope" }
        target = packageName
    }
    fun stop() { id = null; target = ""; allowedApps = emptySet(); automaticApps = false; confirmActions = true }
}
