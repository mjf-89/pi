import { join } from "node:path";
import { existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { createModels, createProvider, type AuthPrompt, type Provider } from "@earendil-works/pi-ai";
import { createRegistry, Harness, type Agent } from "@earendil-works/pi-durable";
import { openNodeSqliteStorage } from "@earendil-works/pi-durable/storage/sqlite/node";
import { openAIResponsesApi } from "pi-source/api/openai-responses.lazy";
import { openaiChatGPTOAuth } from "pi-source/auth/oauth/openai-chatgpt";
import { OPENAI_MODELS } from "pi-source/providers/openai.models";
import { AndroidCredentialStore, type CredentialHost } from "./credentials.ts";
import { loginError } from "./auth-errors.ts";
import { installAndroidDns, type AndroidDnsHost } from "./android-dns.ts";
import { installHttpDiagnostics, recordError, type DiagnosticSink } from "./diagnostics.ts";
import { installOAuthNetworkGate, type OAuthNetworkHost } from "./oauth-network.ts";
import { createDeviceBridge, deviceExtension, type DeviceBridge, type DeviceHost } from "./device-tools.ts";
export { createDeviceBridge } from "./device-tools.ts";

export interface NativeHost extends CredentialHost {
  emit(json: string): void;
  deviceId(): string;
  diagnostic?(json: string): void;
}
export interface AppOptions {
  /** Test injection; the APK calls start with its normal subscription provider. */
  provider?: Provider;
  modelId?: string;
  authenticated?: boolean;
  diagnostics?: DiagnosticSink;
  deviceBridge?: DeviceBridge;
}

const context = BACKGROUND_CONTEXT;
function safeError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message.replace(/Bearer\s+\S+/gi, "Bearer [redacted]")
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, "[redacted]")
    .replace(/\bsk-[A-Za-z0-9_-]+/g, "[redacted]").slice(0, 1200);
}

export async function createApp(host: NativeHost, directory: string, options: AppOptions = {}) {
  const diagnose = options.diagnostics ?? (() => {});
  mkdirSync(directory, { recursive: true });
  const emit = (event: object) => host.emit(JSON.stringify(event));
  const credentials = new AndroidCredentialStore(host);
  const provider = options.provider ?? createProvider({
    id: "openai", name: "OpenAI", baseUrl: "https://api.openai.com/v1",
    auth: { oauth: openaiChatGPTOAuth }, models: Object.values(OPENAI_MODELS), api: openAIResponsesApi(),
  });
  const models = createModels({ credentials });
  models.setProvider(provider);
  let authenticated = options.authenticated ?? Boolean(await credentials.read("openai"));
  const modelId = options.modelId ?? "gpt-6-sol";
  if (!models.getModel(provider.id, modelId)) throw new Error(`Model is not in the pinned catalog: ${modelId}`);
  const registry = createRegistry();
  if (options.deviceBridge) registry.install(deviceExtension(options.deviceBridge));
  const harness = await Harness.open(await openNodeSqliteStorage(join(directory, "conversation.sqlite")), {
    models, registry, settings: { retry: { maxRetries: 2 } },
  }, context);
  const root = await harness.root(context, { agent: { model: { provider: provider.id, modelId } } });
  const defaultDeviceModel = ["gpt-6-luna", "gpt-5.4-mini", "gpt-4.1-mini", modelId]
    .find(id => models.getModel(provider.id, id))!;
  const restorePath = join(directory, "device-model-restore.json");
  type ChatSettings = Pick<Agent, "model" | "thinkingLevel">;
  // A killed device task must not leave the conversation using its temporary model.
  if (existsSync(restorePath)) {
    const saved = JSON.parse(readFileSync(restorePath, "utf8")) as ChatSettings;
    await root.abort(context);
    await root.configure({ model: saved.model ?? null, thinkingLevel: saved.thinkingLevel }, context);
    unlinkSync(restorePath);
  }
  const watch = await root.watch(context);
  let view = watch.value;
  let login: AbortController | undefined;
  let pendingPrompt: { resolve: (value: string) => void; reject: (reason: Error) => void } | undefined;
  let closed = false;
  let deviceRun: AbortController | undefined;
  let deviceFinished: Promise<void> | undefined;

  const publish = () => {
    const messages: { role: string; text: string }[] = [];
    for (const entry of view.entries) {
      for (const message of entry.model ?? []) {
        if (message.role !== "user" && message.role !== "assistant") continue;
        const text = typeof message.content === "string" ? message.content : message.content
          .filter(block => block.type === "text").map(block => block.type === "text" ? block.text : "").join("");
        if (text) messages.push({ role: message.role, text });
      }
    }
    const live = view.docs["pi.live"] as { run?: unknown; generation?: { message?: { content?: { type: string; text?: string }[] } } } | undefined;
    const agent = view.docs["pi.agent"] as { model?: { modelId: string } } | undefined;
    const partial = live?.generation?.message?.content?.filter(block => block.type === "text").map(block => block.text ?? "").join("") ?? "";
    emit({ type: "state", messages, partial, busy: Boolean(live?.run) || Boolean(deviceRun), authenticated, signingIn: Boolean(login), defaultDeviceModel,
      model: agent?.model?.modelId ?? modelId, node: process.versions.node, storage: "SQLite",
      models: models.getModels(provider.id).map(model => model.id) });
  };
  watch.start(async value => { view = value; publish(); });
  publish();
  if (authenticated) harness.resume();

  const prompt = (request: AuthPrompt): Promise<string> => new Promise((resolve, reject) => {
    const abort = () => {
      pendingPrompt = undefined;
      emit({ type: "auth_prompt_closed" });
      reject(new Error("Login cancelled"));
    };
    if (request.signal?.aborted) { abort(); return; }
    request.signal?.addEventListener("abort", abort, { once: true });
    pendingPrompt = {
      resolve: value => { request.signal?.removeEventListener("abort", abort); pendingPrompt = undefined; resolve(value); },
      reject,
    };
    emit({ type: "auth_prompt", message: request.message, promptType: request.type });
  });

  async function command(input: { type: string; text?: string; requestId?: string; modelId?: string; session?: string; deviceModelId?: string; deviceContext?: string }) {
    if (closed) throw new Error("Runtime is closed");
    switch (input.type) {
      case "state": publish(); return;
      case "login": {
        if (login) throw new Error("A login is already in progress.");
        const controller = new AbortController();
        login = controller;
        publish();
        // The user completes login in the system browser; the Node callback stays alive.
        const timeout = setTimeout(() => controller.abort(), 10 * 60_000);
        let stage: "prepare" | "browser" | "exchange" = "prepare";
        diagnose("oauth.start", { stage });
        try {
          await models.login(provider.id, "oauth", {
            signal: controller.signal, prompt,
            notify: event => {
              if (event.type === "auth_url") { stage = "browser"; diagnose("oauth.browser", { stage }); emit({ type: "auth_url", url: event.url }); }
              if (event.type === "progress") { stage = "exchange"; diagnose("oauth.exchange", { stage }); }
              if (event.type === "progress" || event.type === "info") emit({ type: "notice", message: event.message });
            },
          }, { getDeviceId: () => host.deviceId() });
          controller.signal.throwIfAborted();
          authenticated = true;
          diagnose("oauth.success", { stage: "credentials", result: "ok" });
          harness.resume();
          emit({ type: "notice", message: "ChatGPT connected." });
        } catch (error) {
          diagnose("oauth.failure", { stage, result: controller.signal.aborted ? "cancelled" : "failed" });
          recordError(diagnose, stage, error);
          // OAuth failures can contain token response bodies; never forward those to the UI or logs.
          emit({ type: "error", message: controller.signal.aborted ? "Sign-in cancelled or timed out." : loginError(error, stage) });
        } finally {
          clearTimeout(timeout);
          login = undefined;
          pendingPrompt = undefined;
          emit({ type: "auth_prompt_closed" });
          publish();
        }
        return;
      }
      case "cancel_login": login?.abort(); return;
      case "auth_reply": pendingPrompt?.resolve(input.text ?? ""); return;
      case "logout":
        deviceRun?.abort();
        options.deviceBridge?.end();
        login?.abort();
        await root.abort(context);
        await deviceFinished;
        await models.logout(provider.id);
        authenticated = false;
        publish();
        return;
      case "model":
        if (deviceRun) throw new Error("Wait for the device task to finish before changing the chat model.");
        if (!input.modelId || !models.getModel(provider.id, input.modelId)) throw new Error("Unknown model.");
        await root.configure({ model: { provider: provider.id, modelId: input.modelId } }, context);
        return;
      case "send":
      case "device_send": {
        if (deviceRun) throw new Error("A device task is already running.");
        if (!authenticated) throw new Error("Sign in with ChatGPT first.");
        const text = input.text?.trim();
        if (!text || !input.requestId) throw new Error("A message and request ID are required.");
        const deviceTask = input.type === "device_send";
        let previous: ChatSettings | undefined;
        let finishDevice: (() => void) | undefined;
        if (deviceTask) {
          if (!input.session || !options.deviceBridge) throw new Error("Device task bridge is unavailable.");
          options.deviceBridge.begin(input.session, input.deviceContext);
          deviceRun = new AbortController();
          deviceFinished = new Promise(resolve => { finishDevice = resolve; });
          publish();
        }
        try {
          if (deviceTask) {
            const chosen = input.deviceModelId || defaultDeviceModel;
            if (!models.getModel(provider.id, chosen)) throw new Error("Unknown device-task model. Choose another model in Run device task.");
            const agent = await root.agent(context);
            previous = { model: agent.model, thinkingLevel: agent.thinkingLevel };
            writeFileSync(`${restorePath}.tmp`, JSON.stringify(previous));
            renameSync(`${restorePath}.tmp`, restorePath);
            await root.configure({ model: { provider: provider.id, modelId: chosen }, thinkingLevel: "off" }, context);
            deviceRun?.signal.throwIfAborted();
          }
          const submission = await root.submit({ type: "input", content: text, requestId: input.requestId }, context);
          if (deviceTask && deviceRun?.signal.aborted) await root.abort(context);
          const result = await submission.wait(context);
          if (result.status !== "done" && !deviceRun?.signal.aborted) emit({ type: "error", message: "The input was not answered. Check your connection, model selection, and subscription access." });
          return result;
        } catch (error) {
          if (!deviceTask || !deviceRun?.signal.aborted) throw error;
        } finally {
          if (deviceTask) {
            try {
              options.deviceBridge?.end();
              if (previous) {
                await root.configure({ model: previous.model ?? null, thinkingLevel: previous.thinkingLevel }, context);
                if (existsSync(restorePath)) unlinkSync(restorePath);
              }
            } finally { deviceRun = undefined; finishDevice?.(); publish(); }
          }
        }
      }
      case "stop": deviceRun?.abort(); options.deviceBridge?.end(); await root.abort(context); await deviceFinished; return;
      default: throw new Error("Unknown command.");
    }
  }
  return {
    command,
    async close() {
      if (closed) return;
      closed = true;
      deviceRun?.abort();
      options.deviceBridge?.end();
      if (deviceRun) await root.abort(context);
      await deviceFinished;
      login?.abort();
      await watch.stop();
      await harness.close(context);
    },
  };
}

/** Synchronous entry point for Javet. All commands and callbacks stay on its worker thread. */
export function start(host: NativeHost & AndroidDnsHost & OAuthNetworkHost & DeviceHost, directory: string) {
  const diagnose: DiagnosticSink = (event, fields) => host.diagnostic?.(JSON.stringify({ event, fields }));
  diagnose("runtime.start", { nodeVersion: process.versions.node,
    httpProxyConfigured: Boolean(process.env.HTTP_PROXY || process.env.http_proxy),
    httpsProxyConfigured: Boolean(process.env.HTTPS_PROXY || process.env.https_proxy),
    allProxyConfigured: Boolean(process.env.ALL_PROXY || process.env.all_proxy),
    noProxyConfigured: Boolean(process.env.NO_PROXY || process.env.no_proxy) });
  const dns = installAndroidDns(host, diagnose);
  const restoreHttp = installHttpDiagnostics(diagnose);
  const restoreOAuth = installOAuthNetworkGate(host, diagnose, () => {
    host.emit(JSON.stringify({ type: "auth_prompt_closed" }));
    host.emit(JSON.stringify({ type: "notice", message: "Return to Pi Durable to finish sign-in. Waiting for network access…" }));
  });
  const device = createDeviceBridge(host);
  const ready = createApp(host, directory, { deviceBridge: device, diagnostics: (event, fields) => diagnose(event, { ...fields, bridgeInstalled: dns.isInstalled() }) });
  void ready.catch(error => {
    diagnose("runtime.failure", { stage: "runtime" });
    recordError(diagnose, "runtime", error);
    host.emit(JSON.stringify({ type: "fatal", message: safeError(error) }));
  });
  return {
    dnsResult(json: string) { dns.result(json); },
    deviceResult(json: string) { device.result(json); },
    command(json: string) {
      void ready.then(app => app.command(JSON.parse(json))).catch(error =>
        host.emit(JSON.stringify({ type: "error", message: safeError(error) })));
    },
    async close() { restoreOAuth(); try { await (await ready).close(); } finally { restoreHttp(); dns.close(); } },
  };
}
