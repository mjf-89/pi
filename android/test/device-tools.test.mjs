import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm, writeFile, access } from "node:fs/promises";
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
  const faux = fauxProvider({ models: [{ id: "faux-1" }, { id: "gpt-6-luna" }] });
  const calls = [], events = [], ended = [];
  let observedRequest;
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
    (context, options, _state, model) => {
      observedRequest = { context, options, model };
      return fauxAssistantMessage([fauxToolCall("device_open_app", { packageName: "com.android.settings" }, { id: "open-settings" })], { stopReason: "toolUse" });
    },
    fauxAssistantMessage([fauxToolCall("device_act", { action: "tap", nodeId: "1:2", consequential: false }, { id: "about-phone" })], { stopReason: "toolUse" }),
    fauxAssistantMessage("The About phone page reports Model: Test phone."),
  ]);
  let app;
  try {
    app = await createApp({ emit: json => events.push(JSON.parse(json)), readCredential: () => "", writeCredential: () => {}, deviceId: () => "test" }, directory,
      { provider: faux.provider, modelId: "faux-1", authenticated: true, deviceBridge: bridge });
    const result = await app.command({ type: "device_send", text: "Open Settings and report the model from About phone.", requestId: "task-1", session: "native-authorized-task",
      deviceContext: JSON.stringify({ automaticApps: true, confirmActions: false, apps: [{ package: "com.android.settings", name: "Settings" }] }) });
    assert.equal(result.status, "done");
    assert.equal(observedRequest.model.id, "gpt-6-luna");
    assert.match(JSON.stringify(observedRequest.context), /com.android.settings/);
    assert.match(JSON.stringify(observedRequest.context), /automaticApps/);
    assert.equal(observedRequest.options.reasoning, undefined);
    assert.deepEqual(calls.map(call => call.action), ["open", "tap"]);
    assert.equal(calls[1].nodeId, "1:2");
    assert.equal(calls[0].packageName, "com.android.settings");
    assert.equal(calls[1].consequential, false);
    assert.deepEqual(ended, ["native-authorized-task"]);
    assert.equal(faux.state.callCount, 3);
    await app.command({ type: "state" });
    assert.equal(events.at(-1).model, "faux-1");
    assert.equal(events.at(-1).busy, false);
    await assert.rejects(access(join(directory, "device-model-restore.json")));
    assert.ok(events.at(-1).messages.some(message => message.text.includes("Model: Test phone")));
    await assert.rejects(bridge.request({ action: "read" }), /Start a device task/);
  } finally { await app?.close(); await rm(directory, { recursive: true, force: true }); }
});

test("invalid task model ends native access; restart restores the chat model from an interrupted task", async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-device-restore-"));
  const faux = fauxProvider({ models: [{ id: "faux-1" }, { id: "gpt-6-luna" }] });
  const events = [], ended = [];
  const bridge = createDeviceBridge({ deviceRequest: () => assert.fail("No device action expected"), deviceCancel: () => {}, deviceEnd: id => ended.push(id) });
  const host = { emit: json => events.push(JSON.parse(json)), readCredential: () => "", writeCredential: () => {}, deviceId: () => "test" };
  const options = { provider: faux.provider, modelId: "faux-1", authenticated: true, deviceBridge: bridge };
  let app;
  try {
    app = await createApp(host, directory, options);
    await assert.rejects(app.command({ type: "device_send", text: "Navigate", requestId: "bad", session: "invalid-model", deviceModelId: "missing" }), /Unknown device-task model/);
    assert.deepEqual(ended, ["invalid-model"]);
    await app.command({ type: "model", modelId: "gpt-6-luna" });
    await app.close();
    await writeFile(join(directory, "device-model-restore.json"), JSON.stringify({ model: { provider: faux.provider.id, modelId: "faux-1" }, thinkingLevel: "off" }));
    app = await createApp(host, directory, options);
    await app.command({ type: "state" });
    assert.equal(events.at(-1).model, "faux-1");
    assert.equal(events.at(-1).busy, false);
    await assert.rejects(access(join(directory, "device-model-restore.json")));
  } finally { await app?.close(); await rm(directory, { recursive: true, force: true }); }
});

test("Stop cancels a pending device action and restores the chat model before accepting new work", { timeout: 5000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-device-stop-"));
  const faux = fauxProvider({ models: [{ id: "faux-1" }, { id: "gpt-6-luna" }] });
  const events = [], cancelled = [], ended = [];
  let requested;
  const requestStarted = new Promise(resolve => { requested = resolve; });
  const bridge = createDeviceBridge({ deviceRequest: id => requested(id), deviceCancel: id => cancelled.push(id), deviceEnd: id => ended.push(id) });
  faux.setResponses([fauxAssistantMessage([fauxToolCall("device_open_app", {})], { stopReason: "toolUse" })]);
  let app;
  try {
    app = await createApp({ emit: json => events.push(JSON.parse(json)), readCredential: () => "", writeCredential: () => {}, deviceId: () => "test" }, directory,
      { provider: faux.provider, modelId: "faux-1", authenticated: true, deviceBridge: bridge });
    const task = app.command({ type: "device_send", text: "Open Settings", requestId: "stop-task", session: "stop-session" });
    const id = await requestStarted;
    await assert.rejects(app.command({ type: "model", modelId: "faux-1" }), /Wait for the device task/);
    await app.command({ type: "stop" });
    await task;
    assert.deepEqual(cancelled, [id]);
    assert.deepEqual(ended, ["stop-session"]);
    assert.equal(events.filter(event => event.type === "error").length, 0);
    bridge.result(JSON.stringify({ id, result: { ok: true } }));
    await app.command({ type: "state" });
    assert.equal(events.at(-1).model, "faux-1");
    assert.equal(events.at(-1).busy, false);
    await assert.rejects(access(join(directory, "device-model-restore.json")));
  } finally { await app?.close(); await rm(directory, { recursive: true, force: true }); }
});
