import assert from "node:assert/strict";
import { test } from "node:test";
import { installOAuthNetworkGate } from "../runtime/oauth-network.ts";
import { loginError } from "../runtime/auth-errors.ts";

const tokenUrl = "https://auth.openai.com/api/accounts/oauth/token";

test("cancelling or closing a queued exchange never sends its authorization code", async () => {
  const original = globalThis.fetch;
  let sent = 0;
  globalThis.fetch = async () => { sent++; return new Response("ok"); };
  const close = installOAuthNetworkGate({ networkReady: () => false }, () => {}, () => {}, { pollMs: 5 });
  try {
    const controller = new AbortController();
    const cancelled = fetch(tokenUrl, { method: "POST", body: "code=secret", signal: controller.signal });
    const checkCancelled = assert.rejects(cancelled, { name: "AbortError" });
    controller.abort();
    await checkCancelled;
    const closing = fetch(new Request(tokenUrl, { method: "POST", body: "code=secret" }));
    const checkClosing = assert.rejects(closing, { name: "AbortError" });
    close();
    await checkClosing;
    assert.equal(sent, 0);
  } finally { close(); globalThis.fetch = original; }
});

test("network wait is bounded and reports an actionable error without a token request", async () => {
  const original = globalThis.fetch;
  const events = [];
  globalThis.fetch = async () => assert.fail("Must not send a code with no network");
  const close = installOAuthNetworkGate({ networkReady: () => false }, (event, fields) => events.push({ event, fields }), () => {}, { pollMs: 5, timeoutMs: 15 });
  try {
    await assert.rejects(fetch(tokenUrl), error => {
      assert.equal(error.code, "ANDROID_NETWORK_UNAVAILABLE");
      assert.match(loginError(error, "exchange"), /Android network access/);
      return true;
    });
    assert.deepEqual(events.map(event => event.event), ["oauth.network.wait", "oauth.network.timeout"]);
  } finally { close(); globalThis.fetch = original; }
});

test("ready token requests preserve inputs, are sent once, and unrelated requests are not gated", async () => {
  const original = globalThis.fetch;
  const calls = [];
  let ready = false;
  const failure = new Error("transport failure");
  globalThis.fetch = async (input, init) => { calls.push({ input, init }); throw failure; };
  const close = installOAuthNetworkGate({ networkReady: () => ready }, () => {}, () => {});
  try {
    await assert.rejects(fetch("http://localhost:1455/auth/callback"), error => error === failure);
    ready = true;
    const init = { method: "POST", body: "code=private-code", signal: new AbortController().signal };
    await assert.rejects(fetch(tokenUrl, init), error => error === failure);
    assert.equal(calls.length, 2);
    assert.equal(calls[1].input, tokenUrl);
    assert.equal(calls[1].init, init);
  } finally { close(); globalThis.fetch = original; }
});
