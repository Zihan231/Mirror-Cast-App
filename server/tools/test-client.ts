/**
 * Minimal protocol client used by the integration tests and as a CLI:
 *
 *   npm run client -- receiver ws://localhost:8080/ws [--auto-approve]
 *   npm run client -- sender   ws://localhost:8080/ws 123456
 *
 * After pairing, the sender sends a dummy offer, the receiver answers with a dummy
 * answer, and both exchange one ICE candidate, so the full relay path can be eyeballed.
 */
import { pathToFileURL } from "node:url";
import { WebSocket } from "ws";

export type Msg = { type: string; [k: string]: any };

export class TestClient {
  private ws!: WebSocket;
  private readonly inbox: Msg[] = [];
  private waiters: Array<{ match: (m: Msg) => boolean; resolve: (m: Msg) => void }> = [];
  closeCode: number | null = null;
  private closed!: Promise<number>;

  static async connect(url: string, opts: { origin?: string; headers?: Record<string, string> } = {}): Promise<TestClient> {
    const c = new TestClient();
    c.ws = new WebSocket(url, { origin: opts.origin, headers: opts.headers });
    c.closed = new Promise((resolve) => c.ws.once("close", (code) => resolve((c.closeCode = code))));
    c.ws.on("message", (data) => {
      const m = JSON.parse(data.toString()) as Msg;
      const i = c.waiters.findIndex((w) => w.match(m));
      if (i >= 0) c.waiters.splice(i, 1)[0]!.resolve(m);
      else c.inbox.push(m);
    });
    await new Promise<void>((resolve, reject) => {
      c.ws.once("open", () => resolve());
      c.ws.once("error", reject);
      c.ws.once("unexpected-response", (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
    });
    return c;
  }

  send(msg: Msg): void {
    this.ws.send(JSON.stringify(msg));
  }

  sendRaw(text: string): void {
    this.ws.send(text);
  }

  /** Next message of `type` (already received or still to come). */
  next(type: string, timeoutMs = 3000): Promise<Msg> {
    return this.nextWhere((m) => m.type === type, type, timeoutMs);
  }

  nextWhere(match: (m: Msg) => boolean, label = "message", timeoutMs = 3000): Promise<Msg> {
    const i = this.inbox.findIndex(match);
    if (i >= 0) return Promise.resolve(this.inbox.splice(i, 1)[0]!);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w.resolve !== wrapped);
        reject(new Error(`timeout waiting for ${label}`));
      }, timeoutMs);
      const wrapped = (m: Msg): void => {
        clearTimeout(timer);
        resolve(m);
      };
      this.waiters.push({ match, resolve: wrapped });
    });
  }

  /** Sends a request and resolves with the first reply carrying the same id. */
  async request(msg: Msg, timeoutMs = 3000): Promise<Msg> {
    const id = Math.random().toString(36).slice(2);
    const reply = this.nextWhere((m) => m.id === id, `reply to ${msg.type}`, timeoutMs);
    this.send({ ...msg, id });
    return reply;
  }

  /** Asserts nothing arrives for `ms`. */
  async expectSilence(ms = 150): Promise<void> {
    await new Promise((r) => setTimeout(r, ms));
    if (this.inbox.length > 0) throw new Error(`unexpected message: ${JSON.stringify(this.inbox[0])}`);
  }

  waitClosed(timeoutMs = 3000): Promise<number> {
    return Promise.race([
      this.closed,
      new Promise<number>((_, reject) => setTimeout(() => reject(new Error("timeout waiting for close")), timeoutMs)),
    ]);
  }

  close(): void {
    this.ws.close();
  }
}

// --------------------------------------------------------------------- CLI

async function cli(): Promise<void> {
  const [role, url, arg] = process.argv.slice(2);
  if (!role || !url) {
    console.error("usage: test-client <receiver|sender> <ws-url> [code] [--auto-approve]");
    process.exit(2);
  }
  const autoApprove = process.argv.includes("--auto-approve");
  const c = await TestClient.connect(url);
  const show = (dir: string, m: Msg): void => console.log(dir, JSON.stringify(m).slice(0, 300));
  const send = (m: Msg): void => {
    show("→", m);
    c.send(m);
  };

  if (role === "receiver") {
    send({ type: "register-receiver", deviceName: "CLI TV", protocolVersion: 1 });
    await c.next("registered");
    send({ type: "create-pairing" });
    const created = await c.next("pairing-created");
    console.log(`\n  CODE: ${created.code}   (QR payload: ${created.qrPayload})\n`);
    const req = await c.next("pairing-request", 600_000);
    show("←", req);
    if (!autoApprove) console.log("(use --auto-approve; this CLI has no prompt)");
    send({ type: "pairing-decision", pairingId: req.pairingId, approve: autoApprove });
    if (!autoApprove) return c.close();
    show("←", await c.next("paired"));
    show("←", await c.next("offer"));
    send({ type: "answer", sdp: "v=0 (dummy answer)" });
    show("←", await c.next("ice-candidate"));
    send({ type: "ice-candidate", candidate: "candidate:1 1 udp 1 192.0.2.2 9 typ host", sdpMid: "0", sdpMLineIndex: 0 });
    show("←", await c.next("bye", 60_000));
    c.close();
  } else {
    if (!arg) throw new Error("sender needs a code");
    send({ type: "join-pairing", code: arg, deviceName: "CLI Phone", protocolVersion: 1 });
    const first = await c.nextWhere((m) => m.type === "pairing-pending" || m.type === "error", "pending/error");
    show("←", first);
    if (first.type === "error") return c.close();
    const result = await c.nextWhere((m) => m.type === "paired" || m.type === "pairing-denied", "paired/denied", 120_000);
    show("←", result);
    if (result.type !== "paired") return c.close();
    send({ type: "offer", sdp: "v=0 (dummy offer)" });
    show("←", await c.next("answer"));
    send({ type: "ice-candidate", candidate: "candidate:1 1 udp 1 192.0.2.1 9 typ host", sdpMid: "0", sdpMLineIndex: 0 });
    show("←", await c.next("ice-candidate"));
    send({ type: "bye", reason: "done" });
    show("←", await c.next("bye"));
    c.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  cli().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
