export type DiagnosticFields = Record<string, string | number | boolean>;
export type DiagnosticSink = (event: string, fields: DiagnosticFields) => void;

/** Never serialize an Error, its message, stack, request or response body. */
export function recordError(sink: DiagnosticSink, stage: string, error: unknown) {
  const queue: unknown[] = [error];
  const seen = new Set<unknown>();
  for (let depth = 0; queue.length && depth < 6; depth++) {
    const current = queue.shift();
    if (!(current instanceof Error) || seen.has(current)) continue;
    seen.add(current);
    const fields: DiagnosticFields = { stage, depth, errorType: current.name };
    for (const key of ["code", "hostname", "syscall"] as const) {
      const value: unknown = Reflect.get(current, key);
      if (typeof value === "string") fields[key === "hostname" ? "host" : key] = value;
    }
    const status = /^OpenAI OAuth token request failed \((\d{3})\):/.exec(current.message)?.[1];
    if (status) fields.status = Number(status);
    if (current.message === "fetch failed") fields.code = "FETCH_FAILED";
    if (current.message === "ANDROID_CREDENTIAL_WRITE_FAILED") fields.code = current.message;
    sink("error.cause", fields);
    queue.push(current.cause);
    if (current instanceof AggregateError) queue.push(...current.errors.slice(0, 4));
  }
}

export function installHttpDiagnostics(sink: DiagnosticSink) {
  const original = globalThis.fetch;
  let nextId = 0;
  globalThis.fetch = async (input, init) => {
    const id = ++nextId;
    const started = Date.now();
    const url = new URL(typeof input === "string" || input instanceof URL ? input : input.url);
    const operation = url.hostname === "auth.openai.com" && url.pathname.endsWith("/token") ? "oauth_token"
      : url.hostname === "api.openai.com" ? "api_request" : "other";
    sink("http.start", { id, host: url.hostname, operation, method: init?.method ?? (input instanceof Request ? input.method : "GET") });
    try {
      const response = await original(input, init);
      sink("http.response", { id, status: response.status, durationMs: Date.now() - started });
      return response;
    } catch (error) {
      sink("http.failure", { id, durationMs: Date.now() - started });
      recordError(sink, "http", error);
      throw error;
    }
  };
  return () => { globalThis.fetch = original; };
}
