/** Only known error categories reach the UI; OAuth response bodies can contain secrets. */
export function loginError(error: unknown, stage: "prepare" | "browser" | "exchange"): string {
  const message = error instanceof Error ? error.message : "";
  if (message === "ANDROID_CREDENTIAL_WRITE_FAILED") return "ChatGPT authorized sign-in, but Android could not save the credentials securely (ANDROID_CREDENTIAL_WRITE_FAILED).";
  const status = /^OpenAI OAuth token request failed \((\d{3})\):/.exec(message)?.[1];
  if (status) return `ChatGPT rejected the sign-in token exchange (HTTP ${status}). Try signing in again.`;
  const invalidField = /^OpenAI OAuth token response has invalid (access_token|refresh_token|scope|expires_in)$/.exec(message)?.[1];
  if (invalidField) return `ChatGPT returned an unexpected token response (INVALID_${invalidField.toUpperCase()}).`;
  if (message === "OpenAI OAuth token response did not contain an ID token") return "ChatGPT returned a token response without an ID token (MISSING_ID_TOKEN).";
  if (message === "OpenAI OAuth registration callback did not contain an issued client ID") return "The browser callback did not include the registered client ID (MISSING_CLIENT_ID).";
  if (message.startsWith("Port 1455 is in use,")) return "Sign-in cannot start because another app or login is using callback port 1455. Close that login and retry.";
  if (message === "OAuth state mismatch") return "This callback belongs to a different sign-in attempt. Start sign-in again and use its latest callback URL.";
  if (message === "Paste the full callback URL from the browser" || message.startsWith("The pasted callback URL must start with ")) return "Paste the complete localhost callback URL from the browser after signing in.";
  if (message.startsWith("OpenAI OAuth grant did not include ")) return "ChatGPT did not grant direct API access to this app. Check the permissions offered during sign-in.";
  if (message.startsWith("ChatGPT authorization failed:")) return "ChatGPT declined authorization in the browser. Start sign-in again and review the browser's message.";
  const codes = new Set(["ENOTFOUND", "EAI_AGAIN", "ECONNREFUSED", "ECONNRESET", "ETIMEDOUT", "ENETUNREACH", "EHOSTUNREACH", "EACCES", "EPERM", "ERR_SSL_CERTIFICATE_VERIFY_FAILED", "UNABLE_TO_GET_ISSUER_CERT_LOCALLY", "UNABLE_TO_VERIFY_LEAF_SIGNATURE", "DEPTH_ZERO_SELF_SIGNED_CERT", "ERR_TLS_CERT_ALTNAME_INVALID", "CERT_HAS_EXPIRED", "UND_ERR_CONNECT_TIMEOUT"]);
  let current: unknown = error;
  let code: string | undefined;
  for (let depth = 0; depth < 4 && current instanceof Error; depth++) {
    if (current.message === "ANDROID_CREDENTIAL_WRITE_FAILED") return "ChatGPT authorized sign-in, but Android could not save the credentials securely (ANDROID_CREDENTIAL_WRITE_FAILED).";
    if ("code" in current && typeof current.code === "string" && codes.has(current.code)) { code = current.code; break; }
    current = current.cause;
  }
  if (!code && message === "fetch failed") code = "FETCH_FAILED";
  const phase = stage === "prepare" ? "preparing sign-in" : stage === "browser" ? "receiving the browser callback" : "exchanging the sign-in code or saving credentials";
  return `ChatGPT sign-in failed while ${phase}${code ? ` (${code})` : ""}. Try again and report this message if it persists.`;
}
