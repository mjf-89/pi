import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.enums.V8AwaitMode;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.values.reference.V8ValueObject;
import java.nio.file.Path;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class RuntimeProbe {
    public static final class NativeHost {
        String latest = "";
        final ConcurrentLinkedQueue<String> dnsResults = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> deviceResults = new ConcurrentLinkedQueue<>();
        int deviceCalls = 0;
        int dnsLookups = 0;
        int networkChecks = 0;
        @V8Function public void emit(String json) { latest = json; }
        @V8Function public boolean networkReady() { networkChecks++; return true; }
        @V8Function public void deviceRequest(int id, String json) {
            if (!json.contains("embedded-device-session")) throw new AssertionError("Missing device session");
            deviceCalls++;
            deviceResults.offer("{\"id\":" + id + ",\"result\":{\"ok\":true,\"screen\":{\"package\":\"com.android.settings\",\"nodes\":[{\"id\":\"1:0\",\"text\":\"About phone\"}]}}}");
        }
        @V8Function public void deviceCancel(int id) {}
        @V8Function public void deviceEnd(String session) {}
        @V8Function public String readCredential() { return ""; }
        @V8Function public void writeCredential(String value) { throw new AssertionError("No real credentials in this probe"); }
        @V8Function public String deviceId() { return "a17d00f0-7384-4b4a-bdac-667d1d137e48"; }
        @V8Function public void resolveHost(int id, String hostname) throws Exception {
            dnsLookups++;
            StringBuilder json = new StringBuilder("{\"id\":" + id + ",\"addresses\":[");
            for (InetAddress address : InetAddress.getAllByName(hostname)) {
                if (json.charAt(json.length() - 1) != '[') json.append(',');
                json.append("{\"address\":\"").append(address.getHostAddress()).append("\",\"family\":")
                    .append(address instanceof Inet4Address ? 4 : 6).append('}');
            }
            dnsResults.offer(json.append("]}").toString());
        }
    }
    public static void main(String[] args) throws Exception {
        try (NodeRuntime runtime = V8Host.getNodeI18nInstance().createV8Runtime()) {
            System.out.println(runtime.getExecutor("JSON.stringify({node:process.versions.node,fetch:typeof fetch,crypto:typeof crypto,sqlite:typeof require('node:sqlite').DatabaseSync,intl:typeof Intl.Segmenter})").executeString());
            runtime.getExecutor("globalThis.finished = false; setTimeout(() => { globalThis.finished = true; }, 30)").executeVoid();
            long deadline = System.currentTimeMillis() + 5000;
            while (!runtime.getGlobalObject().getBoolean("finished") && System.currentTimeMillis() < deadline) {
                runtime.await(V8AwaitMode.RunNoWait);
                Thread.sleep(5);
            }
            if (!runtime.getGlobalObject().getBoolean("finished")) throw new AssertionError("Timer did not execute");
            System.out.println("Embedded Node timers and SQLite are available.");
            NativeHost host = new NativeHost();
            try (V8ValueObject nativeObject = runtime.createV8ValueObject()) {
                nativeObject.bind(host);
                runtime.getGlobalObject().set("nativeHost", nativeObject);
            }
            runtime.getExecutor(Path.of("test/embedded-smoke.cjs").toAbsolutePath().toFile()).executeVoid();
            deadline = System.currentTimeMillis() + 30000;
            while (!runtime.getGlobalObject().getBoolean("probeFinished") && System.currentTimeMillis() < deadline) {
                String result;
                while ((result = host.dnsResults.poll()) != null) {
                    try (V8ValueObject app = runtime.getGlobalObject().get("dnsProbeApp")) {
                        app.invokeVoid("dnsResult", result);
                    }
                }
                while ((result = host.deviceResults.poll()) != null) {
                    try (V8ValueObject bridge = runtime.getGlobalObject().get("deviceProbe")) {
                        bridge.invokeVoid("result", result);
                    }
                }
                runtime.await(V8AwaitMode.RunNoWait);
                Thread.sleep(5);
            }
            if (!runtime.getGlobalObject().getBoolean("probeFinished")) throw new AssertionError("Pi probe timed out");
            String failure = runtime.getGlobalObject().getString("probeFailure");
            if (!failure.isEmpty()) throw new AssertionError(failure);
            if (host.dnsLookups == 0) throw new AssertionError("HTTP fetch did not use the Java DNS bridge");
            if (host.networkChecks == 0) throw new AssertionError("OAuth did not check Java network readiness");
            if (host.deviceCalls != 1) throw new AssertionError("Device tool did not cross the Java bridge");
            if (!host.latest.contains("Answer from embedded Node.")) throw new AssertionError("Conversation was not restored: " + host.latest);
            try (V8ValueObject nativeObject = runtime.getGlobalObject().get("nativeHost")) {
                nativeObject.unbind(host);
                runtime.getGlobalObject().delete("nativeHost");
            }
            runtime.lowMemoryNotification();
            System.out.println("Pi SQLite chat, Java DNS callbacks, HTTP fetch, and conversation restore passed inside Javet.");
        }
    }
}
