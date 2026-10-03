package dev.pi.android

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject

/** All service and session access runs on the main thread. No screen text is logged here. */
class DeviceAutomation(private val app: PiApplication) {
    private val main = Handler(Looper.getMainLooper())
    val session = DeviceSession { SystemClock.elapsedRealtime() }
    var service: PiAccessibilityService? = null
    @Volatile var running = false
        private set
    private var task = ""
    private var pendingId: Int? = null
    private var pendingAction = ""
    private var reply: ((String) -> Unit)? = null
    private val taskServiceIntent = Intent(app, DeviceTaskService::class.java)

    fun begin(target: String, instruction: String) {
        check(service != null) { "Enable Pi Durable in Android Accessibility settings first." }
        check(instruction.isNotBlank()) { "Enter a device task first." }
        check(target != app.packageName) { "Pi cannot control its own approval UI." }
        val id = session.start(target)
        app.runtime.deviceEvent("device.start")
        task = instruction
        try {
            app.startForegroundService(Intent(app, DeviceTaskService::class.java).putExtra("session", id))
            main.postDelayed({ if (session.id == id) end(true) }, 5 * 60_000)
        } catch (error: Exception) { session.stop(); throw error }
    }

    fun ready(id: String) {
        if (!session.allows(id) || running) return
        try { service?.showControls() ?: error("Accessibility disconnected") }
        catch (_: Exception) { app.runtime.deviceFailure("Could not show device controls. Check that Pi Accessibility is enabled."); end(true); return }
        running = true
        app.runtime.deviceEvent("device.ready", result = "ok")
        app.runtime.send(JSONObject().put("type", "device_send").put("session", id)
            .put("text", task).put("requestId", id))
        task = ""
    }

    fun end(abort: Boolean, expected: String? = null) {
        if (expected != null && session.id != expected) return
        val wasActive = session.id != null
        if (wasActive) app.runtime.deviceEvent("device.stop", result = if (abort) "cancelled" else "ok")
        session.stop()
        running = false
        task = ""
        service?.clearControls()
        if (!abort && wasActive) try { service?.returnToPi() } catch (_: Exception) {}
        finish(JSONObject().put("ok", false).put("error", "Device task stopped."))
        app.stopService(taskServiceIntent)
        if (abort && wasActive) app.runtime.send(JSONObject().put("type", "stop"))
        if (wasActive) app.runtime.send(JSONObject().put("type", "state"))
    }

    fun request(id: Int, json: String, callback: (String) -> Unit) {
        if (pendingId != null) {
            callback(JSONObject().put("id", id).put("result", JSONObject().put("ok", false).put("error", "Another action is pending.")).toString())
            return
        }
        pendingId = id; reply = callback
        try {
            val input = JSONObject(json)
            pendingAction = input.optString("action")
            app.runtime.deviceEvent("device.request", pendingAction)
            val token = input.optString("session")
            check(running && session.consume(token)) { "Device task is inactive, expired, or reached its operation limit." }
            val connected = service ?: error("Accessibility is disconnected.")
            connected.execute(input) { result -> if (pendingId == id) finish(result) }
        } catch (error: Exception) {
            // Error strings are generated locally; never include raw node data or exceptions.
            finish(JSONObject().put("ok", false).put("error", if (error is IllegalStateException) error.message else "Device operation failed."))
        }
    }

    fun cancel(id: Int) { if (pendingId == id) { service?.showControls(); finish(JSONObject().put("ok", false).put("error", "Action cancelled.")) } }
    private fun finish(result: JSONObject) {
        val id = pendingId ?: return
        val callback = reply
        pendingId = null; reply = null
        app.runtime.deviceEvent("device.result", pendingAction, if (result.optBoolean("ok")) "ok" else "failed")
        pendingAction = ""
        callback?.invoke(JSONObject().put("id", id).put("result", result).toString())
    }
}
