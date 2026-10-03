// Loaded by RuntimeProbe.java inside Javet, not by the system node executable.
const { createApp } = require('../app/src/main/assets/pi-runtime.cjs');
const { fauxProvider, fauxAssistantMessage } = require('../node_modules/@earendil-works/pi-ai/dist/index.js');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

globalThis.probeFinished = false;
globalThis.probeFailure = '';
(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-javet-'));
  let app;
  try {
    // Exercise the production provider initialization and the exact native callback binding.
    app = await createApp(globalThis.nativeHost, directory);
    assert.ok(fs.existsSync(path.join(directory, 'conversation.sqlite')));
    await app.close();
    const fake = fauxProvider();
    fake.setResponses([fauxAssistantMessage('Answer from embedded Node.')]);
    const options = { provider: fake.provider, modelId: 'faux-1', authenticated: true };
    app = await createApp(globalThis.nativeHost, directory, options);
    await app.command({ type: 'model', modelId: 'faux-1' });
    const result = await app.command({ type: 'send', text: 'Hello from Java.', requestId: 'javet-smoke-1' });
    assert.equal(result.status, 'done');
    await app.close();
    app = await createApp(globalThis.nativeHost, directory, options);
    await app.command({ type: 'state' });
  } finally {
    await app?.close();
    fs.rmSync(directory, { recursive: true, force: true });
  }
})().catch(error => { globalThis.probeFailure = String(error.stack); })
  .finally(() => { globalThis.probeFinished = true; });
