package dev.pi.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticLogTest {
    @Test fun secretsAndUnexpectedFieldsNeverReachDiskOrExport() {
        val directory = Files.createTempDirectory("pi-diagnostics-").toFile()
        try {
            val file = directory.resolve("events.jsonl")
            val log = DiagnosticLog(file)
            val secret = "private-token-123"
            log.record("http.start", JSONObject().put("id", 1).put("host", "auth.openai.com")
                .put("method", "POST").put("authorization", "Bearer $secret")
                .put("url", "http://localhost:1455/auth/callback?code=$secret")
                .put("body", secret).put("prompt", secret))
            log.record("error.cause", JSONObject().put("code", "ENOTFOUND").put("stage", "exchange")
                .put("errorType", secret).put("stack", secret).put("message", secret))
            log.record("dns.native.start", JSONObject().put("host", "https://example.com/?token=$secret"))
            log.record(secret, JSONObject().put("code", secret))
            val exported = log.snapshot(JSONObject().put("version", "test"))
            assertFalse(exported.contains(secret))
            assertFalse(file.readText().contains(secret))
            assertTrue(exported.contains("ENOTFOUND"))
            assertTrue(exported.contains("auth.openai.com"))
            assertTrue(exported.contains("other-host"))
            assertEquals(3, JSONObject(exported).getJSONArray("events").length())
        } finally { directory.deleteRecursively() }
    }

    @Test fun logSurvivesRestartAndKeepsOnlyTheMostRecent256Events() {
        val directory = Files.createTempDirectory("pi-diagnostics-").toFile()
        try {
            val file = directory.resolve("events.jsonl")
            val log = DiagnosticLog(file)
            repeat(3000) { log.record("dns.lookup.start", JSONObject().put("id", it).put("host", "api.openai.com")) }
            assertTrue(file.length() < 300 * 1024)
            file.appendText("{unfinished")
            val restored = JSONObject(DiagnosticLog(file).snapshot(JSONObject())).getJSONArray("events")
            assertEquals(256, restored.length())
            assertEquals(2999, restored.getJSONObject(255).getJSONObject("fields").getInt("id"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun concurrentResolverAndRuntimeEventsRemainValidJson() {
        val directory = Files.createTempDirectory("pi-diagnostics-").toFile()
        try {
            val log = DiagnosticLog(directory.resolve("events.jsonl"))
            val workers = (0 until 4).map { worker -> Thread {
                repeat(20) { log.record("dns.native.finish", JSONObject().put("id", worker * 20 + it).put("result", "ok")) }
            }.apply { start() } }
            workers.forEach { it.join() }
            assertEquals(80, JSONObject(log.snapshot(JSONObject())).getJSONArray("events").length())
        } finally { directory.deleteRecursively() }
    }
}
