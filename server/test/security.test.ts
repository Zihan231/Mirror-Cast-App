import { afterEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";
import { registerReceiver, startHarness, type Harness } from "./helpers.js";

let h: Harness;
afterEach(async () => {
  await h?.close();
});

describe("brute-force protection", () => {
  it("blocks an IP after repeated failures, even for a valid code", async () => {
    h = await startHarness({ JOIN_MAX_FAILURES: "5" });
    const { code } = await registerReceiver(h);
    const wrong = code === "000000" ? "000001" : "000000";

    // Fresh connection per attempt so the per-connection kick does not mask the per-IP limit.
    for (let i = 0; i < 5; i++) {
      const c = await h.connect();
      c.send({ type: "join-pairing", code: wrong, deviceName: "Mallory", protocolVersion: 1 });
      expect((await c.next("error")).code).toBe("invalid-code");
    }

    const c = await h.connect();
    c.send({ type: "join-pairing", code, deviceName: "Mallory", protocolVersion: 1 });
    const err = await c.next("error");
    expect(err.code).toBe("rate-limited");
    expect(err.retryAfterMs).toBeGreaterThan(0);
  });

  it("drops a connection after 3 wrong codes", async () => {
    h = await startHarness();
    const c = await h.connect();
    for (let i = 0; i < 3; i++) {
      c.send({ type: "join-pairing", code: "123456", deviceName: "Mallory", protocolVersion: 1 });
      expect((await c.next("error")).code).toBe("invalid-code");
    }
    expect(await c.waitClosed()).toBe(1008);
  });

  it("counts malformed join attempts as failures", async () => {
    h = await startHarness({ JOIN_MAX_FAILURES: "2" });
    const { code } = await registerReceiver(h);
    for (let i = 0; i < 2; i++) {
      const c = await h.connect();
      c.send({ type: "join-pairing", code: "12", deviceName: "x", protocolVersion: 1 });
      expect((await c.next("error")).code).toBe("invalid-message");
    }
    const c = await h.connect();
    c.send({ type: "join-pairing", code, deviceName: "x", protocolVersion: 1 });
    expect((await c.next("error")).code).toBe("rate-limited");
  });

  it("rate-limits code creation per IP", async () => {
    h = await startHarness({ CREATE_MAX_PER_MINUTE: "3" });
    const { tv } = await registerReceiver(h); // 1
    expect((await tv.request({ type: "create-pairing" })).type).toBe("pairing-created"); // 2
    expect((await tv.request({ type: "create-pairing" })).type).toBe("pairing-created"); // 3
    expect((await tv.request({ type: "create-pairing" })).code).toBe("rate-limited");
  });
});

describe("message validation", () => {
  it("rejects malformed and out-of-spec messages without crashing", async () => {
    h = await startHarness();
    const c = await h.connect();

    c.sendRaw("not json");
    expect((await c.next("error")).code).toBe("invalid-message");

    c.send({ type: "does-not-exist" });
    expect((await c.next("error")).code).toBe("invalid-message");

    c.send({ type: "register-receiver", deviceName: "TV", protocolVersion: 1, admin: true }); // unknown key
    expect((await c.next("error")).code).toBe("invalid-message");

    c.send({ type: "register-receiver", deviceName: "bad\u0007name", protocolVersion: 1 });
    expect((await c.next("error")).code).toBe("invalid-message");

    // Four invalid messages so far; the connection is still usable (the 5th would drop it).
    expect((await c.request({ type: "ping" })).type).toBe("pong");

    const v = await h.connect();
    v.send({ type: "register-receiver", deviceName: "TV", protocolVersion: 99, id: "r1" });
    expect(await v.next("error")).toMatchObject({ code: "invalid-message", id: "r1" });
  });

  it("disconnects after repeated garbage", async () => {
    h = await startHarness();
    const c = await h.connect();
    for (let i = 0; i < 5; i++) c.sendRaw("{");
    expect(await c.waitClosed()).toBe(1008);
  });

  it("enforces state: no pairing before registering, no double registration", async () => {
    h = await startHarness();
    const c = await h.connect();
    expect((await c.request({ type: "create-pairing" })).code).toBe("wrong-state");
    expect((await c.request({ type: "register-receiver", deviceName: "TV", protocolVersion: 1 })).type).toBe("registered");
    expect((await c.request({ type: "register-receiver", deviceName: "TV", protocolVersion: 1 })).code).toBe("wrong-state");
    expect((await c.request({ type: "get-ice-servers" })).code).toBe("wrong-state");
  });

  it("only the receiver with a matching pending pairing may decide", async () => {
    h = await startHarness();
    const { tv, code } = await registerReceiver(h);
    const attacker = await h.connect();
    attacker.send({ type: "pairing-decision", pairingId: "7b0c1c0e-7f0a-4a52-9d3e-0a4b7d7a1111", approve: true });
    expect((await attacker.next("error")).code).toBe("not-found");

    const phone = await h.connect();
    phone.send({ type: "join-pairing", code, deviceName: "Pixel", protocolVersion: 1 });
    const req = await tv.next("pairing-request");
    phone.send({ type: "pairing-decision", pairingId: req.pairingId, approve: true }); // sender self-approving
    expect((await phone.next("error")).code).toBe("not-found");
    await tv.expectSilence();
  });

  it("closes oversized frames", async () => {
    h = await startHarness({ MAX_PAYLOAD_BYTES: "2048" });
    const c = await h.connect();
    c.sendRaw(JSON.stringify({ type: "ping", pad: "x".repeat(4000) }));
    expect(await c.waitClosed()).toBe(1009);
  });
});

describe("transport policy", () => {
  it("refuses to start without TLS, a trusted proxy, or the explicit insecure flag", () => {
    expect(() => loadConfig({})).toThrow(/transport security/);
    expect(() => loadConfig({ ALLOW_INSECURE_WS: "true" })).not.toThrow();
    expect(() => loadConfig({ TRUST_PROXY: "true" })).not.toThrow();
    expect(() => loadConfig({ TURN_URLS: "turn:x:3478", ALLOW_INSECURE_WS: "true" })).toThrow(/TURN_SECRET/);
  });

  it("lets the dev test page (same origin) connect only when the page is enabled", async () => {
    h = await startHarness({ ENABLE_TEST_PAGE: "true" });
    const host = new URL(h.httpUrl).host;
    const ok = await h.connect({ origin: `http://${host}` });
    expect((await ok.request({ type: "ping" })).type).toBe("pong");
    await expect(h.connect({ origin: "http://evil.example" })).rejects.toThrow(/403/);
    await expect(h.connect({ origin: `http://${host}.evil.example` })).rejects.toThrow(/403/);
  });

  it("does not trust same-origin when the test page is disabled", async () => {
    h = await startHarness();
    const host = new URL(h.httpUrl).host;
    await expect(h.connect({ origin: `http://${host}` })).rejects.toThrow(/403/);
  });

  it("rejects browser origins by default", async () => {
    h = await startHarness();
    await expect(h.connect({ origin: "https://evil.example" })).rejects.toThrow(/403/);
    const ok = await h.connect(); // native clients send no Origin
    expect((await ok.request({ type: "ping" })).type).toBe("pong");
  });

  it("behind a proxy, requires X-Forwarded-Proto=https and rate-limits on the trusted XFF hop", async () => {
    h = await startHarness({ TRUST_PROXY: "true", JOIN_MAX_FAILURES: "1", ALLOW_INSECURE_WS: "false" });
    await expect(h.connect()).rejects.toThrow(/426/); // did not arrive over TLS

    // The left-most XFF entry is client-controlled and must not matter.
    const via = (spoof: string) => ({ headers: { "x-forwarded-proto": "https", "x-forwarded-for": `${spoof}, 203.0.113.9` } });
    const a = await h.connect(via("1.1.1.1"));
    a.send({ type: "join-pairing", code: "123456", deviceName: "x", protocolVersion: 1 });
    expect((await a.next("error")).code).toBe("invalid-code");

    const b = await h.connect(via("2.2.2.2"));
    b.send({ type: "join-pairing", code: "123456", deviceName: "x", protocolVersion: 1 });
    expect((await b.next("error")).code).toBe("rate-limited");
  });
});
