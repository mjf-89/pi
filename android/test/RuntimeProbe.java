import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.enums.V8AwaitMode;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.values.reference.V8ValueObject;
import java.nio.file.Path;

public final class RuntimeProbe {
    public static final class NativeHost {
        String latest = "";
        @V8Function public void emit(String json) { latest = json; }
        @V8Function public String readCredential() { return ""; }
        @V8Function public void writeCredential(String value) { throw new AssertionError("No real credentials in this probe"); }
        @V8Function public String deviceId() { return "a17d00f0-7384-4b4a-bdac-667d1d137e48"; }
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
                runtime.await(V8AwaitMode.RunNoWait);
                Thread.sleep(5);
            }
            if (!runtime.getGlobalObject().getBoolean("probeFinished")) throw new AssertionError("Pi probe timed out");
            String failure = runtime.getGlobalObject().getString("probeFailure");
            if (!failure.isEmpty()) throw new AssertionError(failure);
            if (!host.latest.contains("Answer from embedded Node.")) throw new AssertionError("Conversation was not restored: " + host.latest);
            try (V8ValueObject nativeObject = runtime.getGlobalObject().get("nativeHost")) {
                nativeObject.unbind(host);
                runtime.getGlobalObject().delete("nativeHost");
            }
            runtime.lowMemoryNotification();
            System.out.println("Pi SQLite chat, Java callbacks, and conversation restore passed inside Javet.");
        }
    }
}
