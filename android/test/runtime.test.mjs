import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createRequire } from "node:module";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { fauxProvider, fauxAssistantMessage } from "@earendil-works/pi-ai";

const require = createRequire(import.meta.url);
const { createApp } = require("../app/src/main/assets/pi-runtime.cjs");
function nativeHost() {
  let credential = "";
  const events = [];
  return {
    events, emit: json => events.push(JSON.parse(json)), deviceId: () => "a17d00f0-7384-4b4a-bdac-667d1d137e48",
    readCredential: () => credential, writeCredential: value => { credential = value; },
  };
}
async function waitFor(fn, timeout = 5000) {
  const deadline = Date.now() + timeout;
  while (!fn()) {
    if (Date.now() > deadline) throw new Error("Timed out waiting for runtime state");
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

test("durable conversation persists across reopen and request IDs prevent duplicates", async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-android-test-"));
  const host = nativeHost();
  const faux = fauxProvider();
  faux.setResponses([fauxAssistantMessage("Persisted answer.")]);
  const options = { provider: faux.provider, modelId: "faux-1", authenticated: true };
  let app;
  try {
    app = await createApp(host, directory, options);
    const requestId = randomUUID();
    const input = { type: "send", text: "Remember this conversation.", requestId };
    const result = await app.command(input);
    assert.equal(result.status, "done");
    await waitFor(() => host.events.some(e => e.type === "state" && e.messages.some(m => m.text === "Persisted answer.")));
    await app.close();
    host.events.length = 0;
    app = await createApp(host, directory, options);
    const restored = host.events.find(e => e.type === "state");
    assert.deepEqual(restored.messages.map(m => m.text), ["Remember this conversation.", "Persisted answer."]);
    const duplicate = await app.command(input);
    assert.equal(duplicate.id, result.id);
    await app.command({ type: "state" });
    assert.equal(host.events.at(-1).messages.length, 2);
  } finally {
    await app?.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test("real Pi OAuth callback flow saves credentials without exposing tokens in UI events", async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-android-auth-"));
  const host = nativeHost();
  const realFetch = globalThis.fetch;
  let exchange;
  globalThis.fetch = async (url, init) => {
    assert.equal(String(url), "https://auth.openai.com/api/accounts/oauth/token");
    exchange = new URLSearchParams(init.body);
    return new Response(JSON.stringify({
      access_token: "test-access", refresh_token: "test-refresh", id_token: "test-id",
      expires_in: 3600, scope: "openid chatgpt.tokens.use.direct",
    }), { headers: { "content-type": "application/json" } });
  };
  let app;
  try {
    app = await createApp(host, directory);
    const login = app.command({ type: "login" });
    await waitFor(() => host.events.some(e => e.type === "auth_url"));
    const authorize = new URL(host.events.find(e => e.type === "auth_url").url);
    assert.equal(authorize.searchParams.get("code_challenge_method"), "S256");
    assert.equal(authorize.searchParams.get("ext_agent_host_id"), `urn:uuid:${host.deviceId()}`);
    const callback = new URL(authorize.searchParams.get("redirect_uri"));
    callback.searchParams.set("code", "test-code");
    callback.searchParams.set("state", "wrong-state");
    callback.searchParams.set("client_id", "issued-test-client");
    assert.equal((await realFetch(callback)).status, 400);
    assert.equal(host.readCredential(), "");
    callback.searchParams.set("state", authorize.searchParams.get("state"));
    assert.equal((await realFetch(callback)).status, 200);
    await login;
    assert.equal(exchange.get("client_id"), "issued-test-client");
    assert.equal(exchange.get("code"), "test-code");
    assert.ok(exchange.get("code_verifier"));
    assert.equal(JSON.parse(host.readCredential()).refresh, "test-refresh");
    assert.equal(host.events.filter(e => e.type === "state").at(-1).authenticated, true);
    assert.ok(!JSON.stringify(host.events).includes("test-access"));
    assert.ok(!JSON.stringify(host.events).includes("test-refresh"));
    await app.command({ type: "logout" });
    assert.equal(host.readCredential(), "");
  } finally {
    globalThis.fetch = realFetch;
    await app?.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test("cancelled login saves no credentials and releases the callback port for retry", async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-android-cancel-"));
  const host = nativeHost();
  let app;
  try {
    app = await createApp(host, directory);
    for (let attempt = 0; attempt < 2; attempt++) {
      host.events.length = 0;
      const login = app.command({ type: "login" });
      await waitFor(() => host.events.some(event => event.type === "auth_url"));
      await app.command({ type: "cancel_login" });
      await login;
      assert.equal(host.readCredential(), "");
      const state = host.events.filter(event => event.type === "state").at(-1);
      assert.equal(state.authenticated, false);
      assert.equal(state.signingIn, false);
      assert.ok(host.events.some(event => event.type === "auth_prompt_closed"));
      assert.ok(!host.events.some(event => event.type === "notice" && /callback server unavailable/i.test(event.message)));
    }
  } finally {
    await app?.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test("failed OAuth exchange reports its stage without exposing response bodies or credentials", async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-android-auth-error-"));
  const host = nativeHost();
  const realFetch = globalThis.fetch;
  const failures = [
    () => new Response('secret-code=private-code&refresh_token=private-refresh', { status: 403 }),
    () => { throw new TypeError("fetch failed private-code", { cause: Object.assign(new Error("private-refresh"), { code: "ENOTFOUND" }) }); },
    () => new Response(JSON.stringify({ access_token: "private-code", refresh_token: "private-refresh", id_token: "private-id", expires_in: 3600, scope: "openid chatgpt.tokens.use.direct" })),
  ];
  let app;
  try {
    app = await createApp(host, directory);
    for (const [index, failure] of failures.entries()) {
      host.events.length = 0;
      globalThis.fetch = async () => failure();
      if (index === 2) host.writeCredential = () => { throw new Error("Private native failure with private-refresh"); };
      const login = app.command({ type: "login" });
      await waitFor(() => host.events.some(e => e.type === "auth_url"));
      const authorize = new URL(host.events.find(e => e.type === "auth_url").url);
      const callback = new URL(authorize.searchParams.get("redirect_uri"));
      callback.searchParams.set("code", "private-code");
      callback.searchParams.set("state", authorize.searchParams.get("state"));
      callback.searchParams.set("client_id", "issued-test-client");
      await realFetch(callback);
      await login;
      const error = host.events.find(e => e.type === "error");
      assert.match(error.message, [/HTTP 403/, /exchanging.*ENOTFOUND/, /ANDROID_CREDENTIAL_WRITE_FAILED/][index]);
      assert.doesNotMatch(JSON.stringify(host.events), /private-code|private-refresh|account must support/);
      assert.equal(host.readCredential(), "");
      assert.equal(host.events.filter(e => e.type === "state").at(-1).authenticated, false);
    }
  } finally {
    globalThis.fetch = realFetch;
    await app?.close();
    await rm(directory, { recursive: true, force: true });
  }
});
