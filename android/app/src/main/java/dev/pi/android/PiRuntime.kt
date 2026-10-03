package dev.pi.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.values.reference.V8ValueObject
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Application-owned worker: opening a browser or rotating the activity keeps the runtime alive. */
class PiRuntime(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val commands = LinkedBlockingQueue<String>()
    private val credentials = SecureCredentials(context)
    private val listeners = linkedSetOf<(JSONObject) -> Unit>()
    private var latestState: JSONObject? = null
    private var fatal: JSONObject? = null
    private var authPrompt: JSONObject? = null
    private var authUrl: String? = null
    private var started = false

    fun attach(listener: (JSONObject) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        latestState?.let(listener)
        authPrompt?.let(listener)
        fatal?.let(listener)
    }
    fun browserUrl(): String? = authUrl
    fun detach(listener: (JSONObject) -> Unit) { listeners.remove(listener) }
    fun send(command: JSONObject) { commands.offer(command.toString()) }

    @Synchronized fun start() {
        if (started) return
        started = true
        Thread({ runNode() }, "pi-embedded-node").start()
    }
    private fun publish(json: String) {
        main.post {
            val event = JSONObject(json)
            if (event.optString("type") == "state") latestState = event
            if (event.optString("type") == "fatal") fatal = event
            if (event.optString("type") == "auth_url") authUrl = event.getString("url")
            if (event.optString("type") == "auth_prompt") authPrompt = event
            if (event.optString("type") == "auth_prompt_closed") { authPrompt = null; authUrl = null }
            listeners.toList().forEach { it(event) }
        }
    }

    // Annotated methods expose only these four functions, rather than general JVM access.
    inner class NativeHost {
        @V8Function fun emit(json: String) { publish(json) }
        @V8Function fun readCredential(): String = credentials.read()
        @V8Function fun writeCredential(value: String) { credentials.write(value) }
        @V8Function fun deviceId(): String = credentials.deviceId()
    }

    private fun runNode() {
        try {
            val directory = File(context.filesDir, "pi").apply { mkdirs() }
            val bundle = File(directory, "pi-runtime.cjs")
            context.assets.open("pi-runtime.cjs").use { source ->
                bundle.outputStream().use { target -> source.copyTo(target) }
            }
            V8Host.getNodeI18nInstance().createV8Runtime<NodeRuntime>().use { node ->
                node.createV8ValueObject().use { native ->
                    native.bind(NativeHost())
                    node.globalObject.set("nativeHost", native)
                }
                val bootstrap = "globalThis.piApp = require(" + JSONObject.quote(bundle.absolutePath) +
                    ").start(globalThis.nativeHost," + JSONObject.quote(directory.absolutePath) + ");"
                node.getExecutor(bootstrap).executeVoid()
                node.globalObject.get<V8ValueObject>("piApp").use { app ->
                    while (!Thread.currentThread().isInterrupted) {
                        val command = commands.poll(10, TimeUnit.MILLISECONDS)
                        if (command != null) app.invokeVoid("command", command)
                        node.await(V8AwaitMode.RunNoWait)
                    }
                }
            }
        } catch (error: Throwable) {
            // Never log JS source, callback URLs, or credentials from runtime exceptions.
            publish(JSONObject().put("type", "fatal")
                .put("message", "Embedded Node could not run (${error.javaClass.simpleName}). This device/runtime combination needs diagnosis.").toString())
        }
    }
}
