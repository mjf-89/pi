import { setTimeout as delay } from "node:timers/promises";
import type { DiagnosticSink } from "./diagnostics.ts";

export interface OAuthNetworkHost {
  /** True only when the Activity is resumed and Android exposes an active network. */
  networkReady(): boolean;
}

/** Keep the one-use authorization code pending while the browser is foregrounded. */
export function installOAuthNetworkGate(host: OAuthNetworkHost, diagnose: DiagnosticSink,
  onWaiting: () => void, options: { pollMs?: number; timeoutMs?: number } = {}) {
  const original = globalThis.fetch;
  const closed = new AbortController();
  globalThis.fetch = async (input, init) => {
    const url = new URL(typeof input === "string" || input instanceof URL ? input : input.url);
    if (url.origin === "https://auth.openai.com" && url.pathname === "/api/accounts/oauth/token") {
      const requestSignal = init?.signal ?? (input instanceof Request ? input.signal : undefined);
      const signal = AbortSignal.any([closed.signal, ...(requestSignal ? [requestSignal] : [])]);
      const started = Date.now();
      signal.throwIfAborted();
      if (!host.networkReady()) {
        diagnose("oauth.network.wait", {});
        onWaiting();
        while (!host.networkReady()) {
          signal.throwIfAborted();
          if (Date.now() - started >= (options.timeoutMs ?? 120_000)) {
            diagnose("oauth.network.timeout", { durationMs: Date.now() - started });
            throw Object.assign(new Error("Android network unavailable for sign-in"), { code: "ANDROID_NETWORK_UNAVAILABLE" });
          }
          await delay(options.pollMs ?? 250, undefined, { signal });
        }
      }
      signal.throwIfAborted();
      diagnose("oauth.network.ready", { durationMs: Date.now() - started });
    }
    // Preserve the original URL, TLS validation, body and cancellation. Never retry a sent code.
    return original(input, init);
  };
  return () => { globalThis.fetch = original; closed.abort(); };
}
