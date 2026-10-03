import assert from "node:assert/strict";
import { test } from "node:test";
import { installHttpDiagnostics, recordError } from "../runtime/diagnostics.ts";

test("HTTP diagnostics preserve requests and record status without credentials, URLs or bodies", async () => {
  const events = [];
  const original = globalThis.fetch;
  let received;
  globalThis.fetch = async (input, init) => {
    received = { input, init };
    return new Response('{"access_token":"private-response"}', { status: 200 });
  };
  const restore = installHttpDiagnostics((event, fields) => events.push({ event, fields }));
  try {
    const url = "https://auth.openai.com/api/accounts/oauth/token?private-query";
    const init = { method: "POST", headers: { authorization: "Bearer private-header" }, body: "code=private-code" };
    const response = await fetch(url, init);
    assert.equal(received.input, url);
    assert.equal(received.init, init);
    assert.equal((await response.json()).access_token, "private-response");
    assert.equal(events[0].fields.operation, "oauth_token");
    assert.equal(events[1].fields.status, 200);
    assert.doesNotMatch(JSON.stringify(events), /private-|authorization|access_token/);
  } finally { restore(); globalThis.fetch = original; }
});

test("failure diagnostics retain DNS cause codes without serializing error messages or stacks", async () => {
  const events = [];
  const original = globalThis.fetch;
  const cause = Object.assign(new Error("private-dns-message"), { code: "ENOTFOUND", hostname: "auth.openai.com", syscall: "getaddrinfo" });
  const error = new TypeError("fetch failed private-token", { cause });
  globalThis.fetch = async () => { throw error; };
  const restore = installHttpDiagnostics((event, fields) => events.push({ event, fields }));
  try {
    await assert.rejects(fetch("https://auth.openai.com/api/accounts/oauth/token"), value => value === error);
    assert.ok(events.some(entry => entry.fields.code === "ENOTFOUND"));
    assert.doesNotMatch(JSON.stringify(events), /private-|stack|message/);
    const statusEvents = [];
    recordError((event, fields) => statusEvents.push({ event, fields }), "exchange", new Error("OpenAI OAuth token request failed (403): private-token-response"));
    assert.equal(statusEvents[0].fields.status, 403);
    assert.doesNotMatch(JSON.stringify(statusEvents), /private-/);
  } finally { restore(); globalThis.fetch = original; }
});
