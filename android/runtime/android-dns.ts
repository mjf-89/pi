import dns, { type LookupAddress, type LookupOptions } from "node:dns";
import { syncBuiltinESMExports } from "node:module";
import { isIP } from "node:net";
import type { DiagnosticSink } from "./diagnostics.ts";

export interface AndroidDnsHost {
  resolveHost(id: number, hostname: string): void;
}
type LookupCallback = (error: NodeJS.ErrnoException | null, address: string | LookupAddress[], family?: number) => void;

/** Javet's native resolver cannot reliably use Android's network/VPN DNS configuration. */
export function installAndroidDns(host: AndroidDnsHost, diagnose: DiagnosticSink = () => {}) {
  const original = dns.lookup;
  const originalPromise = dns.promises.lookup;
  let nextId = 0;
  const pending = new Map<number, { finish: (code?: string, addresses?: LookupAddress[]) => void }>();

  function lookup(hostname: string, optionsOrCallback: number | LookupOptions | LookupCallback, callback?: LookupCallback): void {
    // Preserve Node's handling of IP literals and empty hostnames, including loopback callbacks.
    if (!hostname || isIP(hostname)) {
      Reflect.apply(original, dns, [hostname, optionsOrCallback, callback].filter(value => value !== undefined));
      return;
    }
    const options = typeof optionsOrCallback === "number" ? { family: optionsOrCallback }
      : typeof optionsOrCallback === "function" ? {} : optionsOrCallback ?? {};
    const reply = typeof optionsOrCallback === "function" ? optionsOrCallback : callback;
    if (typeof reply !== "function") throw new TypeError("DNS lookup requires a callback");
    const family = options.family === "IPv4" ? 4 : options.family === "IPv6" ? 6 : options.family ?? 0;
    if (![0, 4, 6].includes(family)) throw new TypeError("DNS family must be 0, 4, or 6");
    const order = options.order ?? (options.verbatim === false ? "ipv4first" : options.verbatim === true ? "verbatim" : dns.getDefaultResultOrder());
    const id = ++nextId;
    const started = Date.now();
    diagnose("dns.lookup.start", { id, host: hostname, family, all: Boolean(options.all), hints: options.hints ?? 0 });
    const timer = setTimeout(() => finish("EAI_AGAIN"), 15_000);
    const finish = (code?: string, resolved: LookupAddress[] = []) => {
      if (!pending.delete(id)) return;
      clearTimeout(timer);
      let addresses = resolved.filter(entry => !family || entry.family === family);
      if (family === 6 && ((options.hints ?? 0) & dns.V4MAPPED) && (!addresses.length || ((options.hints ?? 0) & dns.ALL))) {
        addresses = addresses.concat(resolved.filter(entry => entry.family === 4).map(entry => ({ address: `::ffff:${entry.address}`, family: 6 })));
      }
      if (order !== "verbatim") addresses.sort((a, b) => order === "ipv4first" ? a.family - b.family : b.family - a.family);
      const error = code || !addresses.length
        ? Object.assign(new Error(`Android DNS lookup failed (${code ?? "ENOTFOUND"})`), { code: code ?? "ENOTFOUND", syscall: "getaddrinfo", hostname })
        : null;
      diagnose("dns.lookup.finish", { id, result: error ? "failed" : "ok", ...(error?.code ? { code: error.code } : {}), durationMs: Date.now() - started,
        ipv4Count: addresses.filter(address => address.family === 4).length, ipv6Count: addresses.filter(address => address.family === 6).length });
      // Match Node's asynchronous callback behavior, including native synchronous failures.
      process.nextTick(() => {
        if (options.all) reply(error, error ? [] : addresses);
        else reply(error, error ? "" : addresses[0].address, error ? 0 : addresses[0].family);
      });
    };
    pending.set(id, { finish });
    try { host.resolveHost(id, hostname); }
    catch { finish("EAI_AGAIN"); }
  }

  // Node overloads the callback shape according to options.all; lookup handles both forms above.
  dns.lookup = lookup as typeof dns.lookup;
  dns.promises.lookup = ((hostname: string, options: number | LookupOptions = {}) => new Promise((resolve, reject) => {
    lookup(hostname, options, (error, address, family) => {
      if (error) reject(error);
      else resolve(Array.isArray(address) ? address : { address, family: family ?? isIP(address) });
    });
  })) as typeof dns.promises.lookup;
  syncBuiltinESMExports();
  diagnose("dns.bridge.installed", { bridgeInstalled: dns.lookup === lookup });

  return {
    isInstalled() { return dns.lookup === lookup; },
    result(json: string) {
      const result = JSON.parse(json) as { id: number; code?: string; addresses?: LookupAddress[] };
      pending.get(result.id)?.finish(result.code, result.addresses);
    },
    close() {
      for (const request of pending.values()) request.finish("ECANCELED");
      dns.lookup = original;
      dns.promises.lookup = originalPromise;
      syncBuiltinESMExports();
    },
  };
}
