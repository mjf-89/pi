package dev.pi.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.ArrayDeque

/** A bounded, app-private log. Only explicitly approved fields are persisted or exported. */
class DiagnosticLog(private val file: File) {
    private val entries = ArrayDeque<JSONObject>()
    init {
        try { if (file.isFile && file.length() <= 512 * 1024) {
            file.useLines { lines -> lines.forEach { line ->
                try {
                    val old = JSONObject(line)
                    val clean = sanitize(old.optString("event"), old.optJSONObject("fields") ?: JSONObject())
                    if (clean != null) {
                        clean.put("time", old.optString("time").takeIf { it.matches(Regex("[0-9T:.Z+-]{1,40}")) } ?: "unknown")
                        entries.addLast(clean)
                        if (entries.size > 256) entries.removeFirst()
                    }
                } catch (_: Exception) { /* Ignore an incomplete final line after process termination. */ }
            } }
            file.writeText(entries.joinToString("\n") + if (entries.isEmpty()) "" else "\n")
        } } catch (_: Exception) { /* An unreadable diagnostic file must not prevent startup. */ }
    }

    @Synchronized fun record(event: String, fields: JSONObject = JSONObject()) {
        val entry = sanitize(event, fields) ?: return
        entry.put("time", Instant.now().toString())
        entries.addLast(entry)
        if (entries.size > 256) entries.removeFirst()
        try {
            file.parentFile?.mkdirs()
            if (file.length() > 256 * 1024) file.writeText(entries.joinToString("\n") + "\n")
            else file.appendText(entry.toString() + "\n")
        } catch (_: Exception) { /* Keep the in-memory log available when storage is unavailable. */ }
    }

    @Synchronized fun snapshot(metadata: JSONObject): String = JSONObject()
        .put("formatVersion", 1)
        .put("exportedAt", Instant.now().toString())
        .put("app", metadata)
        .put("privacy", "No tokens, authorization codes, callback URLs, headers, response bodies, device IDs or conversation text are recorded.")
        .put("events", JSONArray(entries.toList()))
        .toString(2)

    private fun sanitize(event: String, fields: JSONObject): JSONObject? {
        if (event !in EVENTS) return null
        val clean = JSONObject()
        fields.keys().forEach { key ->
            val value = fields.opt(key)
            when {
                key in NUMBERS && value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1_000_000_000.0 -> clean.put(key, value)
                key in BOOLEANS && value is Boolean -> clean.put(key, value)
                key == "host" && value is String -> clean.put(key, if (value in HOSTS) value else "other-host")
                key == "nodeVersion" && value is String && value.matches(Regex("[0-9.]{1,24}")) -> clean.put(key, value)
                key in STRINGS && value is String && value in STRINGS.getValue(key) -> clean.put(key, value)
            }
        }
        return JSONObject().put("event", event).put("fields", clean)
    }

    companion object {
        private val EVENTS = setOf("app.start", "app.foreground", "runtime.start", "runtime.failure", "oauth.start", "oauth.browser", "oauth.exchange", "oauth.success", "oauth.failure", "oauth.network.wait", "oauth.network.ready", "oauth.network.timeout", "http.start", "http.response", "http.failure", "error.cause", "dns.bridge.installed", "dns.lookup.start", "dns.lookup.finish", "dns.native.start", "dns.native.finish", "network.snapshot", "credentials.read", "credentials.write", "diagnostics.export")
        private val HOSTS = setOf("auth.openai.com", "api.openai.com", "localhost", "127.0.0.1", "::1")
        private val NUMBERS = setOf("id", "family", "hints", "ipv4Count", "ipv6Count", "dnsServerCount", "durationMs", "status", "depth", "versionCode")
        private val BOOLEANS = setOf("all", "foreground", "dataSaver", "powerSave", "backgroundRestricted", "activeNetwork", "wifi", "cellular", "ethernet", "vpn", "internet", "validated", "privateDns", "privateDnsConfigured", "bridgeInstalled", "httpProxyConfigured", "httpsProxyConfigured", "allProxyConfigured", "noProxyConfigured")
        private val STRINGS = mapOf(
            "stage" to setOf("prepare", "browser", "exchange", "credentials", "runtime", "http", "dns"),
            "result" to setOf("ok", "failed", "cancelled", "timeout", "present", "absent"),
            "operation" to setOf("oauth_token", "api_request", "other"),
            "method" to setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
            "syscall" to setOf("getaddrinfo", "connect", "lookup", "read", "write"),
            "errorType" to setOf("Error", "TypeError", "AggregateError", "AbortError", "ModelsError", "UnknownHostException", "SecurityException", "GaiException", "IOException", "SocketTimeoutException", "JavetException", "JavetExecutionException", "JavetCompilationException", "IllegalStateException", "RuntimeException"),
            "code" to setOf("ENOTFOUND", "EAI_AGAIN", "EAI_NODATA", "EAI_NONAME", "EAI_FAIL", "EAI_SYSTEM", "EAI_MEMORY", "EAI_FAMILY", "EAI_SERVICE", "EAI_SOCKTYPE", "EAI_BADFLAGS", "ECONNREFUSED", "ECONNRESET", "ETIMEDOUT", "ENETUNREACH", "EHOSTUNREACH", "EACCES", "EPERM", "ECANCELED", "ANDROID_NETWORK_UNAVAILABLE", "ERR_SSL_CERTIFICATE_VERIFY_FAILED", "UNABLE_TO_GET_ISSUER_CERT_LOCALLY", "UNABLE_TO_VERIFY_LEAF_SIGNATURE", "DEPTH_ZERO_SELF_SIGNED_CERT", "ERR_TLS_CERT_ALTNAME_INVALID", "CERT_HAS_EXPIRED", "UND_ERR_CONNECT_TIMEOUT", "FETCH_FAILED", "ANDROID_CREDENTIAL_WRITE_FAILED")
        )
    }
}
