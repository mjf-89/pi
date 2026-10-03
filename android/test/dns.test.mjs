import assert from "node:assert/strict";
import dns from "node:dns";
import { createServer } from "node:http";
import { createServer as createHttpsServer, get as httpsGet } from "node:https";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { installAndroidDns } from "../runtime/android-dns.ts";

test("fetch uses Android DNS for a hostname absent from the native resolver", async () => {
  const hostname = "pi-dns-test.invalid";
  await assert.rejects(dns.promises.lookup(hostname), { code: "ENOTFOUND" });
  const server = createServer((request, response) => {
    response.setHeader("content-type", "application/json");
    response.end(JSON.stringify({ host: request.headers.host }));
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const lookups = [];
  const bridge = installAndroidDns({ resolveHost(id, name) {
    lookups.push(name);
    setImmediate(() => bridge.result(JSON.stringify({ id, addresses: [{ address: "127.0.0.1", family: 4 }] })));
  } });
  try {
    const response = await fetch(`http://${hostname}:${server.address().port}/oauth/token`, { method: "POST", body: new URLSearchParams({ code: "fake-code" }) });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).host, `${hostname}:${server.address().port}`);
    assert.deepEqual(lookups, [hostname]);
  } finally {
    bridge.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test("Android resolver handles address families, ordering, literals, failures and late results", async () => {
  const requests = [];
  const bridge = installAndroidDns({ resolveHost(id, hostname) { requests.push({ id, hostname }); } });
  const addresses = [{ address: "::1", family: 6 }, { address: "127.0.0.1", family: 4 }];
  const reply = value => bridge.result(JSON.stringify({ id: requests.at(-1).id, ...value }));
  try {
    const both = dns.promises.lookup("example.invalid", { all: true, order: "ipv4first" });
    reply({ addresses });
    assert.deepEqual(await both, [addresses[1], addresses[0]]);
    const ipv6 = dns.promises.lookup("example.invalid", 6);
    reply({ addresses });
    assert.deepEqual(await ipv6, addresses[0]);
    const missing = dns.promises.lookup("example.invalid", 4);
    reply({ addresses: [addresses[0]] });
    await assert.rejects(missing, { code: "ENOTFOUND" });
    const failed = dns.promises.lookup("example.invalid");
    reply({ code: "EAI_AGAIN" });
    await assert.rejects(failed, { code: "EAI_AGAIN" });
    const count = requests.length;
    assert.deepEqual(await dns.promises.lookup("127.0.0.1"), addresses[1]);
    assert.equal(requests.length, count);
    const cancelled = dns.promises.lookup("example.invalid");
    bridge.close();
    reply({ addresses });
    await assert.rejects(cancelled, { code: "ECANCELED" });
  } finally { bridge.close(); }
});

test("HTTPS retains the original hostname and rejects a certificate for a different host", async () => {
  const directory = await mkdtemp(join(tmpdir(), "pi-dns-tls-"));
  const keyPath = join(directory, "key.pem");
  const certPath = join(directory, "cert.pem");
  execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=pi-dns-test.invalid", "-addext", "subjectAltName=DNS:pi-dns-test.invalid", "-keyout", keyPath, "-out", certPath], { stdio: "ignore" });
  const cert = await readFile(certPath);
  const server = createHttpsServer({ key: await readFile(keyPath), cert }, (_request, response) => response.end("verified"));
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const bridge = installAndroidDns({ resolveHost(id) {
    setImmediate(() => bridge.result(JSON.stringify({ id, addresses: [{ address: "127.0.0.1", family: 4 }] })));
  } });
  const request = hostname => new Promise((resolve, reject) => {
    httpsGet({ hostname, port: server.address().port, ca: cert, agent: false }, response => {
      response.resume();
      response.on("end", () => resolve(response.statusCode));
    }).on("error", reject);
  });
  try {
    assert.equal(await request("pi-dns-test.invalid"), 200);
    await assert.rejects(request("wrong-host.invalid"), { code: "ERR_TLS_CERT_ALTNAME_INVALID" });
  } finally {
    bridge.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
