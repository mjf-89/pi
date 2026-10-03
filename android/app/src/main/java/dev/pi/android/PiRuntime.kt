package dev.pi.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.values.reference.V8ValueObject
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/** Application-owned worker: opening a browser or rotating the activity keeps the runtime alive. */
class PiRuntime(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val commands = LinkedBlockingQueue<String>()
    private val dnsResults = LinkedBlockingQueue<String>()
    private val dnsExecutor = Executors.newFixedThreadPool(2)
    private val diagnostics = DiagnosticLog(File(context.filesDir, "diagnostics/events.jsonl"))
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

    private fun recordNetwork() {
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val capabilities = manager.getNetworkCapabilities(network)
            val properties = manager.getLinkProperties(network)
            val fields = JSONObject().put("activeNetwork", network != null)
                .put("wifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("cellular", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                .put("ethernet", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
                .put("vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                .put("internet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                .put("dnsServerCount", properties?.dnsServers?.size ?: 0)
            if (Build.VERSION.SDK_INT >= 28) fields.put("privateDns", properties?.isPrivateDnsActive == true)
                .put("privateDnsConfigured", !properties?.privateDnsServerName.isNullOrEmpty())
            diagnostics.record("network.snapshot", fields)
        } catch (error: Exception) { recordFailure("dns", error) }
    }

    private fun recordFailure(stage: String, error: Throwable) {
        var cause: Throwable? = error
        repeat(4) { depth ->
            val current = cause ?: return
            val fields = JSONObject().put("stage", stage).put("depth", depth)
                .put("errorType", current.javaClass.simpleName)
            // Android resolver messages can contain hostnames; retain only a known EAI code.
            Regex("\\bEAI_[A-Z_]+\\b").find(current.message ?: "")?.value?.let { fields.put("code", it) }
            diagnostics.record("error.cause", fields)
            cause = current.cause
        }
    }

    @Suppress("DEPRECATION")
    fun exportDiagnostics(output: OutputStream) {
        recordNetwork()
        diagnostics.record("diagnostics.export")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val metadata = JSONObject().put("package", context.packageName).put("version", info.versionName)
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            .put("androidSdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
        try {
            val build = context.assets.open("build-info.json").bufferedReader().use { JSONObject(it.readText()) }
            for (key in listOf("appCommit", "piCommit")) {
                val value = build.optString(key)
                if (value.matches(Regex("[a-f0-9]{40}"))) metadata.put(key, value)
            }
        } catch (_: Exception) { /* Version and package still identify the installed APK. */ }
        output.write(diagnostics.snapshot(metadata).toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        diagnostics.record("app.start")
        recordNetwork()
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

    // Only explicit bridge methods are exposed to Node, not general JVM access.
    inner class NativeHost {
        @V8Function fun emit(json: String) { publish(json) }
        @V8Function fun readCredential(): String = try {
            credentials.read().also { diagnostics.record("credentials.read", JSONObject().put("result", if (it.isEmpty()) "absent" else "present")) }
        } catch (error: Exception) { recordFailure("credentials", error); throw error }
        @V8Function fun writeCredential(value: String) {
            try {
                credentials.write(value)
                diagnostics.record("credentials.write", JSONObject().put("result", "ok"))
            } catch (error: Exception) {
                diagnostics.record("credentials.write", JSONObject().put("result", "failed"))
                recordFailure("credentials", error)
                throw error
            }
        }
        @V8Function fun deviceId(): String = credentials.deviceId()
        @V8Function fun diagnostic(json: String) {
            try {
                val record = JSONObject(json)
                diagnostics.record(record.optString("event"), record.optJSONObject("fields") ?: JSONObject())
            } catch (_: Exception) { /* Diagnostics must not interrupt sign-in. */ }
        }
        @V8Function fun resolveHost(id: Int, hostname: String) {
            dnsExecutor.execute {
                val started = System.nanoTime()
                recordNetwork()
                diagnostics.record("dns.native.start", JSONObject().put("id", id).put("host", hostname))
                val result = JSONObject().put("id", id)
                try {
                    // Uses Android's active network resolver, including VPN and Private DNS.
                    val addresses = JSONArray()
                    InetAddress.getAllByName(hostname).forEach { address ->
                        addresses.put(JSONObject().put("address", address.hostAddress)
                            .put("family", if (address is Inet4Address) 4 else 6))
                    }
                    result.put("addresses", addresses)
                } catch (error: UnknownHostException) { result.put("code", "ENOTFOUND"); recordFailure("dns", error) }
                catch (error: SecurityException) { result.put("code", "EACCES"); recordFailure("dns", error) }
                catch (error: Exception) { result.put("code", "EAI_AGAIN"); recordFailure("dns", error) }
                val resolved = result.optJSONArray("addresses") ?: JSONArray()
                val families = (0 until resolved.length()).map { resolved.getJSONObject(it).getInt("family") }
                val fields = JSONObject().put("id", id).put("result", if (result.has("code")) "failed" else "ok")
                    .put("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .put("ipv4Count", families.count { it == 4 }).put("ipv6Count", families.count { it == 6 })
                if (result.has("code")) fields.put("code", result.getString("code"))
                diagnostics.record("dns.native.finish", fields)
                // V8 may only be called on its owner thread; no UI events carry resolver results.
                dnsResults.offer(result.toString())
            }
        }
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
                        dnsResults.poll()?.let { app.invokeVoid("dnsResult", it) }
                        node.await(V8AwaitMode.RunNoWait)
                    }
                }
            }
        } catch (error: Throwable) {
            diagnostics.record("runtime.failure")
            recordFailure("runtime", error)
            // Never log JS source, callback URLs, or credentials from runtime exceptions.
            publish(JSONObject().put("type", "fatal")
                .put("message", "Embedded Node could not run (${error.javaClass.simpleName}). This device/runtime combination needs diagnosis.").toString())
        } finally {
            dnsExecutor.shutdownNow()
        }
    }
}
