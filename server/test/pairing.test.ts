import { createHmac } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import { pairedSession, registerReceiver, sleep, startHarness, type Harness } from "./helpers.js";

let h: Harness;
afterEach(async () => {
  await h?.close();
});

describe("pairing", () => {
  it("pairs with explicit approval, relays signaling, and ends cleanly", async () => {
    const secret = "s".repeat(32);
    h = await startHarness({
      PUBLIC_URL: "wss://cast.example.com/ws",
      STUN_URLS: "stun:turn.example.com:3478",
      TURN_URLS: "turn:turn.example.com:3478?transport=udp",
      TURN_SECRET: secret,
    });
    const { tv, code } = await registerReceiver(h);

    const phone = await h.connect();
    const created = await tv.request({ type: "create-pairing" }); // re-issue replaces the old code
    expect(created.qrPayload).toBe(`mirror://pair?code=${created.code}&server=${encodeURIComponent("wss://cast.example.com/ws")}`);
    expect(created.ttlSeconds).toBe(300);

    // The first code is dead after re-issue.
    phone.send({ type: "join-pairing", code, deviceName: "Pixel", protocolVersion: 1 });
    expect((await phone.next("error")).code).toBe("invalid-code");

    phone.send({ type: "join-pairing", code: created.code, deviceName: "Pixel 9", protocolVersion: 1 });
    const pending = await phone.next("pairing-pending");
    const req = await tv.next("pairing-request");
    expect(req.senderName).toBe("Pixel 9");
    expect(req.pairingId).toBe(pending.pairingId);

    // Nothing is relayed or established until the TV approves.
    phone.send({ type: "offer", sdp: "v=0" });
    expect((await phone.next("error")).code).toBe("wrong-state");

    tv.send({ type: "pairing-decision", pairingId: req.pairingId, approve: true });
    const [pPhone, pTv] = await Promise.all([phone.next("paired"), tv.next("paired")]);
    expect(pPhone.role).toBe("sender");
    expect(pTv.role).toBe("receiver");
    expect(pPhone.peerName).toBe("Living Room TV");
    expect(pTv.peerName).toBe("Pixel 9");
    expect(pPhone.sessionId).toBe(pTv.sessionId);

    // TURN REST credentials: coturn recomputes HMAC-SHA1(secret, username).
    const turn = pPhone.iceServers.find((s: any) => s.credential);
    const [expiry] = turn.username.split(":");
    expect(Number(expiry)).toBeGreaterThan(Date.now() / 1000 + 3600);
    expect(turn.credential).toBe(createHmac("sha1", secret).update(turn.username).digest("base64"));
    expect(pPhone.iceServers[0].urls).toEqual(["stun:turn.example.com:3478"]);

    phone.send({ type: "offer", sdp: "v=0 offer" });
    expect(await tv.next("offer")).toEqual({ type: "offer", sdp: "v=0 offer" });
    tv.send({ type: "answer", sdp: "v=0 answer" });
    expect(await phone.next("answer")).toEqual({ type: "answer", sdp: "v=0 answer" });
    phone.send({ type: "ice-candidate", candidate: "candidate:1 1 udp 1 10.0.0.2 9 typ host", sdpMid: "0", sdpMLineIndex: 0 });
    expect(await tv.next("ice-candidate")).toMatchObject({ candidate: expect.stringContaining("10.0.0.2"), sdpMid: "0", sdpMLineIndex: 0 });

    const fresh = await phone.request({ type: "get-ice-servers" });
    expect(fresh.iceServers.length).toBe(2);

    phone.send({ type: "bye", reason: "user-stopped" });
    expect((await tv.next("bye")).reason).toBe("user-stopped");
  });

  it("codes are single use", async () => {
    h = await startHarness();
    const { tv, code } = await registerReceiver(h);
    const a = await h.connect();
    a.send({ type: "join-pairing", code, deviceName: "A", protocolVersion: 1 });
    const req = await tv.next("pairing-request");
    tv.send({ type: "pairing-decision", pairingId: req.pairingId, approve: true });
    await a.next("paired");

    const b = await h.connect();
    b.send({ type: "join-pairing", code, deviceName: "B", protocolVersion: 1 });
    expect((await b.next("error")).code).toBe("invalid-code");
  });

  it("a rejected pairing notifies the sender and consumes the code", async () => {
    h = await startHarness();
    const { tv, code } = await registerReceiver(h);
    const phone = await h.connect();
    phone.send({ type: "join-pairing", code, deviceName: "Stranger", protocolVersion: 1 });
    const req = await tv.next("pairing-request");
    tv.send({ type: "pairing-decision", pairingId: req.pairingId, approve: false });
    expect(await phone.next("pairing-denied")).toMatchObject({ reason: "rejected" });

    phone.send({ type: "join-pairing", code, deviceName: "Stranger", protocolVersion: 1 });
    expect((await phone.next("error")).code).toBe("invalid-code");

    // TV can issue a new code afterwards.
    expect((await tv.request({ type: "create-pairing" })).type).toBe("pairing-created");
  });

  it("denies when the TV does not answer in time", async () => {
    h = await startHarness({ APPROVAL_TIMEOUT_SECONDS: "1" });
    const { tv, code } = await registerReceiver(h);
    const phone = await h.connect();
    phone.send({ type: "join-pairing", code, deviceName: "Pixel", protocolVersion: 1 });
    await tv.next("pairing-request");
    expect(await phone.next("pairing-denied")).toMatchObject({ reason: "timeout" });
    expect(await tv.next("pairing-denied")).toMatchObject({ reason: "timeout" });
  });

  it("expires codes after the TTL (indistinguishable from a wrong code)", async () => {
    h = await startHarness({ PAIRING_TTL_SECONDS: "1" });
    const { tv, code, pairingId } = await registerReceiver(h);
    expect(await tv.next("pairing-expired", 2500)).toMatchObject({ pairingId });

    const phone = await h.connect();
    phone.send({ type: "join-pairing", code, deviceName: "Late", protocolVersion: 1 });
    const err = await phone.next("error");
    expect(err.code).toBe("invalid-code");
    expect(err.message).toBe("invalid or expired code");
  });

  it("rejects an expired code even if the sweeper has not run yet", async () => {
    h = await startHarness({ PAIRING_TTL_SECONDS: "1", SWEEP_INTERVAL_MS: "60000" });
    const { code } = await registerReceiver(h);
    await sleep(1100);
    const phone = await h.connect();
    phone.send({ type: "join-pairing", code, deviceName: "Late", protocolVersion: 1 });
    expect((await phone.next("error")).code).toBe("invalid-code");
  });

  it("tells the peer when the other side disconnects mid-session", async () => {
    h = await startHarness();
    const { tv, phone } = await pairedSession(h);
    phone.close();
    expect((await tv.next("bye")).reason).toBe("peer-disconnected");
    // Receiver is reusable.
    expect((await tv.request({ type: "create-pairing" })).type).toBe("pairing-created");
  });

  it("notifies the sender when the TV disappears while awaiting approval", async () => {
    h = await startHarness();
    const { tv, code } = await registerReceiver(h);
    const phone = await h.connect();
    phone.send({ type: "join-pairing", code, deviceName: "Pixel", protocolVersion: 1 });
    await tv.next("pairing-request");
    tv.close();
    expect(await phone.next("pairing-denied")).toMatchObject({ reason: "receiver-gone" });
  });

  it("enforces offer/answer direction", async () => {
    h = await startHarness();
    const { tv, phone } = await pairedSession(h);
    tv.send({ type: "offer", sdp: "v=0" });
    expect((await tv.next("error")).code).toBe("forbidden");
    phone.send({ type: "answer", sdp: "v=0" });
    expect((await phone.next("error")).code).toBe("forbidden");
    await phone.expectSilence();
  });

  it("answers ping and serves /health", async () => {
    h = await startHarness();
    const c = await h.connect();
    const pong = await c.request({ type: "ping", ts: 42 });
    expect(pong).toMatchObject({ type: "pong", ts: 42 });
    const res = await fetch(`${h.httpUrl}/health`);
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ status: "ok", connections: 1 });
  });
});
