import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createRequire } from "node:module";
import { fauxProvider, fauxAssistantMessage, fauxToolCall } from "@earendil-works/pi-ai";

const require = createRequire(import.meta.url);
const { createApp, createDeviceBridge } = require("../app/src/main/assets/pi-runtime.cjs");

test("device bridge rejects idle calls, pins the native session, and ignores late cancelled results", async () => {
  const calls = [], cancelled = [], ended = [];
  const bridge = createDeviceBridge({ deviceRequest: (id, json) => calls.push({ id, ...JSON.parse(json) }),
    deviceCancel: id => cancelled.push(id), deviceEnd: id => ended.push(id) });
  await assert.rejects(bridge.request({ action: "read" }), /Start a device task/);
  assert.equal(calls.length, 0);
  bridge.begin("approved-task");
  const controller = new AbortController();
  const first = bridge.request({ action: "tap", session: "model-invented", nodeId: "1:2" }, controller.signal);
  assert.equal(calls[0].session, "approved-task");
  const rejection = assert.rejects(first, /cancelled/);
  controller.abort();
  await rejection;
  bridge.result(JSON.stringify({ id: calls[0].id, result: { ok: true } }));
  const second = bridge.request({ action: "read" });
  const stopped = assert.rejects(second, /stopped/);
  bridge.end();
  await stopped;
  assert.deepEqual(cancelled, calls.map(call => call.id));
  assert.deepEqual(ended, ["approved-task"]);
  await assert.rejects(bridge.request({ action: "read" }), /Start a device task/);
});

test("Pi opens Settings, acts on a fresh control, and reads the resulting screen through its tool loop", async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-device-tools-"));
  const faux = fauxProvider();
  const calls = [], events = [], ended = [];
  let bridge;
  bridge = createDeviceBridge({
    deviceRequest: (id, json) => {
      const input = JSON.parse(json); calls.push(input);
      assert.equal(input.session, "native-authorized-task");
      const screen = input.action === "open"
        ? { package: "com.android.settings", nodes: [{ id: "1:2", text: "About phone", actions: ["tap"] }] }
        : { package: "com.android.settings", nodes: [{ id: "2:1", text: "Model: Test phone", actions: [] }] };
      queueMicrotask(() => bridge.result(JSON.stringify({ id, result: { ok: true, screen } })));
    }, deviceCancel: () => {}, deviceEnd: id => ended.push(id),
  });
  faux.setResponses([
    fauxAssistantMessage([fauxToolCall("device_open_app", {}, { id: "open-settings" })], { stopReason: "toolUse" }),
    fauxAssistantMessage([fauxToolCall("device_act", { action: "tap", nodeId: "1:2" }, { id: "about-phone" })], { stopReason: "toolUse" }),
    fauxAssistantMessage("The About phone page reports Model: Test phone."),
  ]);
  let app;
  try {
    app = await createApp({ emit: json => events.push(JSON.parse(json)), readCredential: () => "", writeCredential: () => {}, deviceId: () => "test" }, directory,
      { provider: faux.provider, modelId: "faux-1", authenticated: true, deviceBridge: bridge });
    const result = await app.command({ type: "device_send", text: "Open Settings and report the model from About phone.", requestId: "task-1", session: "native-authorized-task" });
    assert.equal(result.status, "done");
    assert.deepEqual(calls.map(call => call.action), ["open", "tap"]);
    assert.equal(calls[1].nodeId, "1:2");
    assert.deepEqual(ended, ["native-authorized-task"]);
    assert.equal(faux.state.callCount, 3);
    await app.command({ type: "state" });
    assert.ok(events.at(-1).messages.some(message => message.text.includes("Model: Test phone")));
    await assert.rejects(bridge.request({ action: "read" }), /Start a device task/);
  } finally { await app?.close(); await rm(directory, { recursive: true, force: true }); }
});
