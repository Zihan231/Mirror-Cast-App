import { createHmac, randomBytes, randomInt, randomUUID } from "node:crypto";
import type { Config } from "./config.js";
import type { Logger } from "./logger.js";
import {
  ClientMessageSchema,
  PROTOCOL_VERSION,
  type ClientMessage,
  type ClientMessageOf,
  type DenyReason,
  type ErrorCode,
  type ServerMessage,
} from "./protocol.js";
import { FailureLimiter, TokenBucket, WindowCounter, type Clock } from "./rateLimit.js";
import type { PairingStore } from "./store.js";
import { buildIceServers } from "./turn.js";

/** What the hub needs from a socket; keeps the hub testable without the network. */
export interface Transport {
  send(data: string): void;
  close(code: number, reason: string): void;
}

export interface ClientHandle {
  onMessage(raw: string): void;
  onClose(): void;
}

export interface HubStats {
  connections: number;
  receivers: number;
  pendingApprovals: number;
  sessions: number;
  activePairings: number;
}

type Role = "none" | "receiver" | "sender";

interface Client {
  id: string;
  ip: string;
  transport: Transport;
  role: Role;
  deviceName?: string;
  receiverId?: string;
  pairingId?: string; // receiver: outstanding code OR pending approval; sender: pending approval
  pending?: Pending;
  session?: Session;
  bucket: TokenBucket;
  invalidCount: number;
  codeFailures: number;
  queue: Promise<void>;
  closed: boolean;
  idleTimer?: NodeJS.Timeout;
}

interface Pending {
  pairingId: string;
  receiver: Client;
  sender: Client;
  timer: NodeJS.Timeout;
}

interface Session {
  sessionId: string;
  receiver: Client;
  sender: Client;
}

const MAX_INVALID_MESSAGES = 5;
const MAX_CODE_FAILURES_PER_CONNECTION = 3;

export class SignalingHub {
  /** Set by the server once the listening address is known (LAN mode). */
  publicUrl: string | null;

  private readonly clients = new Set<Client>();
  private readonly receivers = new Map<string, Client>();
  private readonly pendings = new Map<string, Pending>();
  private readonly sessions = new Map<string, Session>();
  private readonly joinLimiter: FailureLimiter;
  private readonly createLimiter: WindowCounter;
  private readonly codeSecret: Buffer;
  private sweeper?: NodeJS.Timeout;

  constructor(
    private readonly config: Config,
    private readonly store: PairingStore,
    private readonly log: Logger,
    private readonly now: Clock = Date.now,
  ) {
    this.publicUrl = config.publicUrl;
    this.joinLimiter = new FailureLimiter(config.joinMaxFailures, config.joinFailureWindowMs, config.joinBlockMs, now);
    this.createLimiter = new WindowCounter(config.createMaxPerMinute, 60_000, now);
    this.codeSecret = config.pairingSecret ? Buffer.from(config.pairingSecret) : randomBytes(32);
  }

  start(): void {
    this.sweeper = setInterval(() => void this.sweep(), this.config.sweepIntervalMs);
    this.sweeper.unref();
  }

  stop(): void {
    if (this.sweeper) clearInterval(this.sweeper);
    for (const p of this.pendings.values()) clearTimeout(p.timer);
  }

  async stats(): Promise<HubStats> {
    return {
      connections: this.clients.size,
      receivers: this.receivers.size,
      pendingApprovals: this.pendings.size,
      sessions: this.sessions.size,
      activePairings: await this.store.size(),
    };
  }

  /** Tell everyone the server is going away; the caller then closes sockets. */
  announceShutdown(): void {
    for (const s of this.sessions.values()) {
      this.send(s.receiver, { type: "bye", reason: "server-shutdown" });
      this.send(s.sender, { type: "bye", reason: "server-shutdown" });
    }
  }

  attach(transport: Transport, ip: string): ClientHandle {
    const c: Client = {
      id: randomUUID(),
      ip,
      transport,
      role: "none",
      bucket: new TokenBucket(100, 50, this.now),
      invalidCount: 0,
      codeFailures: 0,
      queue: Promise.resolve(),
      closed: false,
    };
    this.clients.add(c);
    // Connections that never register or join are dropped.
    c.idleTimer = setTimeout(() => {
      if (c.role === "none") this.kick(c, 1008, "idle");
    }, this.config.idleAuthTimeoutMs);
    c.idleTimer.unref();

    return {
      onMessage: (raw) => {
        // Serialise per connection so async store calls cannot interleave two messages.
        c.queue = c.queue
          .then(() => this.onRaw(c, raw))
          .catch((err) => {
            this.log.error({ err, client: c.id }, "handler failed");
            this.err(c, "invalid-message", "internal error");
          });
      },
      onClose: () => void this.onClose(c),
    };
  }

  // ---------------------------------------------------------------- inbound

  private async onRaw(c: Client, raw: string): Promise<void> {
    if (c.closed) return;
    if (!c.bucket.take()) {
      this.log.warn({ client: c.id, ip: c.ip }, "message rate exceeded");
      this.kick(c, 1008, "rate limit");
      return;
    }

    let json: unknown;
    try {
      json = JSON.parse(raw);
    } catch {
      return this.invalid(c, "malformed JSON");
    }

    const parsed = ClientMessageSchema.safeParse(json);
    if (!parsed.success) {
      // A malformed join-pairing is still a probe against the code space.
      if (typeof json === "object" && json !== null && (json as { type?: unknown }).type === "join-pairing") {
        this.joinLimiter.recordFailure(c.ip);
      }
      const issue = parsed.error.issues[0];
      const id = typeof json === "object" && json !== null ? (json as { id?: unknown }).id : undefined;
      return this.invalid(c, issue ? `${issue.path.join(".") || "message"}: ${issue.message}` : "invalid message", typeof id === "string" ? id.slice(0, 64) : undefined);
    }

    const msg = parsed.data;
    switch (msg.type) {
      case "ping":
        return this.send(c, { type: "pong", id: msg.id, ts: msg.ts, serverTime: this.now() });
      case "register-receiver":
        return this.registerReceiver(c, msg);
      case "create-pairing":
        return this.createPairing(c, msg);
      case "join-pairing":
        return this.joinPairing(c, msg);
      case "pairing-decision":
        return this.pairingDecision(c, msg);
      case "offer":
      case "answer":
      case "ice-candidate":
        return this.relay(c, msg);
      case "get-ice-servers":
        return this.getIceServers(c, msg);
      case "bye":
        return this.bye(c, msg);
    }
  }

  private invalid(c: Client, message: string, id?: string): void {
    c.invalidCount += 1;
    this.err(c, "invalid-message", message, id);
    if (c.invalidCount >= MAX_INVALID_MESSAGES) this.kick(c, 1008, "too many invalid messages");
  }

  private registerReceiver(c: Client, msg: ClientMessageOf<"register-receiver">): void {
    if (c.role !== "none") return this.err(c, "wrong-state", "already registered", msg.id);
    c.role = "receiver";
    c.deviceName = msg.deviceName;
    c.receiverId = randomUUID();
    this.receivers.set(c.receiverId, c);
    this.clearIdle(c);
    this.log.info({ client: c.id, receiverId: c.receiverId }, "receiver registered");
    this.send(c, { type: "registered", id: msg.id, receiverId: c.receiverId, serverTime: this.now(), protocolVersion: PROTOCOL_VERSION });
  }

  private async createPairing(c: Client, msg: ClientMessageOf<"create-pairing">): Promise<void> {
    if (c.role !== "receiver" || !c.receiverId) return this.err(c, "wrong-state", "register as receiver first", msg.id);
    if (c.session || c.pending) return this.err(c, "busy", "receiver has an active or pending session", msg.id);
    if (!this.createLimiter.hit(c.ip)) return this.err(c, "rate-limited", "too many pairing codes requested", msg.id, 60_000);
    if ((await this.store.size()) >= this.config.maxActivePairings) return this.err(c, "busy", "server at capacity", msg.id);

    await this.store.removeByReceiver(c.receiverId); // one live code per receiver
    const createdAt = this.now();
    const expiresAt = createdAt + this.config.pairingTtlMs;
    const pairingId = randomUUID();

    let code = "";
    let stored = false;
    for (let i = 0; i < 20 && !stored; i++) {
      code = randomInt(0, 1_000_000).toString().padStart(6, "0");
      stored = await this.store.add({ pairingId, codeHash: this.hashCode(code), receiverId: c.receiverId, createdAt, expiresAt });
    }
    if (!stored) return this.err(c, "busy", "could not allocate a code", msg.id);

    c.pairingId = pairingId;
    this.log.info({ client: c.id, pairingId, expiresAt }, "pairing created");
    this.send(c, {
      type: "pairing-created",
      id: msg.id,
      pairingId,
      code,
      expiresAt,
      ttlSeconds: Math.round(this.config.pairingTtlMs / 1000),
      qrPayload: this.publicUrl ? `mirror://pair?code=${code}&server=${encodeURIComponent(this.publicUrl)}` : null,
    });
  }

  private async joinPairing(c: Client, msg: ClientMessageOf<"join-pairing">): Promise<void> {
    if (c.role !== "none") return this.err(c, "wrong-state", "already in a role", msg.id);

    const retry = this.joinLimiter.retryAfterMs(c.ip);
    if (retry > 0) return this.err(c, "rate-limited", "too many failed attempts", msg.id, retry);

    // Expired, unknown and already-used codes are indistinguishable on purpose.
    const rec = await this.store.take(this.hashCode(msg.code), this.now());
    if (!rec) return this.joinFailed(c, msg.id);

    const receiver = this.receivers.get(rec.receiverId);
    if (!receiver || receiver.closed || receiver.session || receiver.pending) {
      return this.err(c, "receiver-unavailable", "receiver is not available; request a new code", msg.id);
    }

    c.role = "sender";
    c.deviceName = msg.deviceName;
    this.clearIdle(c);

    const pairingId = rec.pairingId;
    const pending: Pending = {
      pairingId,
      receiver,
      sender: c,
      timer: setTimeout(() => this.endPending(pending, "timeout"), this.config.approvalTimeoutMs),
    };
    pending.timer.unref();
    this.pendings.set(pairingId, pending);
    receiver.pending = pending;
    receiver.pairingId = pairingId;
    c.pending = pending;
    c.pairingId = pairingId;

    this.log.info({ pairingId, sender: c.id, receiver: receiver.id }, "join accepted, awaiting approval");
    this.send(c, { type: "pairing-pending", id: msg.id, pairingId, approvalTimeoutMs: this.config.approvalTimeoutMs });
    this.send(receiver, {
      type: "pairing-request",
      pairingId,
      senderName: msg.deviceName,
      approvalTimeoutMs: this.config.approvalTimeoutMs,
    });
  }

  private joinFailed(c: Client, id?: string): void {
    this.joinLimiter.recordFailure(c.ip);
    c.codeFailures += 1;
    this.log.warn({ ip: c.ip, client: c.id }, "join failed");
    this.err(c, "invalid-code", "invalid or expired code", id);
    if (c.codeFailures >= MAX_CODE_FAILURES_PER_CONNECTION) this.kick(c, 1008, "too many failed attempts");
  }

  private pairingDecision(c: Client, msg: ClientMessageOf<"pairing-decision">): void {
    const p = c.pending;
    if (c.role !== "receiver" || !p || p.pairingId !== msg.pairingId) {
      return this.err(c, "not-found", "no such pending pairing", msg.id);
    }
    if (!msg.approve) return this.endPending(p, "rejected");

    clearTimeout(p.timer);
    this.pendings.delete(p.pairingId);
    p.receiver.pending = undefined;
    p.sender.pending = undefined;

    const session: Session = { sessionId: randomUUID(), receiver: p.receiver, sender: p.sender };
    this.sessions.set(session.sessionId, session);
    p.receiver.session = session;
    p.sender.session = session;
    this.log.info({ sessionId: session.sessionId, pairingId: p.pairingId }, "paired");

    const now = this.now();
    this.send(p.sender, {
      type: "paired",
      sessionId: session.sessionId,
      role: "sender",
      peerName: p.receiver.deviceName ?? "Receiver",
      iceServers: buildIceServers(this.config, now),
    });
    this.send(p.receiver, {
      type: "paired",
      sessionId: session.sessionId,
      role: "receiver",
      peerName: p.sender.deviceName ?? "Sender",
      iceServers: buildIceServers(this.config, now),
    });
  }

  private relay(c: Client, msg: Extract<ClientMessage, { type: "offer" | "answer" | "ice-candidate" }>): void {
    const s = c.session;
    if (!s) return this.err(c, "wrong-state", "not in a session", msg.id);
    // Direction is fixed: the sender offers, the receiver answers.
    if (msg.type === "offer" && c !== s.sender) return this.err(c, "forbidden", "only the sender may send offers", msg.id);
    if (msg.type === "answer" && c !== s.receiver) return this.err(c, "forbidden", "only the receiver may send answers", msg.id);

    const peer = c === s.sender ? s.receiver : s.sender;
    // Rebuild the message from validated fields; never forward raw client JSON.
    switch (msg.type) {
      case "offer":
      case "answer":
        this.send(peer, { type: msg.type, sdp: msg.sdp });
        break;
      case "ice-candidate":
        this.send(peer, { type: "ice-candidate", candidate: msg.candidate, sdpMid: msg.sdpMid ?? null, sdpMLineIndex: msg.sdpMLineIndex ?? null });
        break;
    }
  }

  private getIceServers(c: Client, msg: ClientMessageOf<"get-ice-servers">): void {
    if (!c.session) return this.err(c, "wrong-state", "not in a session", msg.id);
    this.send(c, { type: "ice-servers", id: msg.id, iceServers: buildIceServers(this.config, this.now()) });
  }

  private bye(c: Client, msg: ClientMessageOf<"bye">): void {
    const reason = msg.reason ?? "bye";
    if (c.session) return this.endSession(c.session, reason);
    if (c.pending) {
      const p = c.pending;
      if (c === p.receiver) return this.endPending(p, "rejected");
      return this.cancelPending(p);
    }
    if (c.role === "receiver" && c.receiverId) {
      // Withdraw an unused code.
      void this.store.removeByReceiver(c.receiverId);
      c.pairingId = undefined;
    }
  }

  // ------------------------------------------------------------- lifecycle

  /** Receiver rejected, approval timed out, or receiver vanished. */
  private endPending(p: Pending, reason: DenyReason): void {
    if (!this.pendings.delete(p.pairingId)) return;
    clearTimeout(p.timer);
    p.receiver.pending = undefined;
    p.receiver.pairingId = undefined;
    p.sender.pending = undefined;
    p.sender.pairingId = undefined;
    p.sender.role = "none"; // may try again with a fresh code
    p.sender.codeFailures = 0;
    this.armIdle(p.sender);
    this.send(p.sender, { type: "pairing-denied", pairingId: p.pairingId, reason });
    if (reason === "timeout") this.send(p.receiver, { type: "pairing-denied", pairingId: p.pairingId, reason });
  }

  /** Sender withdrew while waiting for approval. */
  private cancelPending(p: Pending): void {
    if (!this.pendings.delete(p.pairingId)) return;
    clearTimeout(p.timer);
    p.receiver.pending = undefined;
    p.receiver.pairingId = undefined;
    p.sender.pending = undefined;
    p.sender.pairingId = undefined;
    p.sender.role = "none";
    this.armIdle(p.sender);
    this.send(p.receiver, { type: "bye", reason: "cancelled" });
  }

  private endSession(s: Session, reason: string): void {
    if (!this.sessions.delete(s.sessionId)) return;
    for (const side of [s.receiver, s.sender]) {
      side.session = undefined;
      side.pairingId = undefined;
    }
    s.sender.role = "none";
    this.armIdle(s.sender);
    this.send(s.receiver, { type: "bye", reason });
    this.send(s.sender, { type: "bye", reason });
    this.log.info({ sessionId: s.sessionId, reason }, "session ended");
  }

  private async onClose(c: Client): Promise<void> {
    if (c.closed) return;
    c.closed = true;
    this.clearIdle(c);
    this.clients.delete(c);

    if (c.role === "receiver" && c.receiverId) {
      this.receivers.delete(c.receiverId);
      await this.store.removeByReceiver(c.receiverId);
    }
    if (c.session) {
      const s = c.session;
      const peer = c === s.receiver ? s.sender : s.receiver;
      this.sessions.delete(s.sessionId);
      s.receiver.session = undefined;
      s.sender.session = undefined;
      peer.pairingId = undefined;
      if (peer.role === "sender") {
        peer.role = "none";
        this.armIdle(peer);
      }
      this.send(peer, { type: "bye", reason: "peer-disconnected" });
      this.log.info({ sessionId: s.sessionId }, "session ended: peer disconnected");
    } else if (c.pending) {
      const p = c.pending;
      if (c === p.receiver) this.endPending(p, "receiver-gone");
      else this.cancelPending(p);
    }
  }

  private async sweep(): Promise<void> {
    try {
      const expired = await this.store.sweepExpired(this.now());
      for (const rec of expired) {
        const r = this.receivers.get(rec.receiverId);
        if (r && !r.closed && r.pairingId === rec.pairingId) {
          r.pairingId = undefined;
          this.send(r, { type: "pairing-expired", pairingId: rec.pairingId });
        }
      }
      this.joinLimiter.sweep();
      this.createLimiter.sweep();
    } catch (err) {
      this.log.error({ err }, "sweep failed");
    }
  }

  // --------------------------------------------------------------- helpers

  private hashCode(code: string): string {
    return createHmac("sha256", this.codeSecret).update(code).digest("hex");
  }

  private send(c: Client, msg: ServerMessage): void {
    if (c.closed) return;
    try {
      c.transport.send(JSON.stringify(msg));
    } catch (err) {
      this.log.warn({ err, client: c.id }, "send failed");
    }
  }

  private err(c: Client, code: ErrorCode, message: string, id?: string, retryAfterMs?: number): void {
    this.send(c, { type: "error", id, code, message, ...(retryAfterMs ? { retryAfterMs } : {}) });
  }

  private kick(c: Client, code: number, reason: string): void {
    try {
      c.transport.close(code, reason);
    } catch {
      /* socket already gone */
    }
  }

  private clearIdle(c: Client): void {
    if (c.idleTimer) clearTimeout(c.idleTimer);
    c.idleTimer = undefined;
  }

  private armIdle(c: Client): void {
    if (c.closed) return;
    this.clearIdle(c);
    c.idleTimer = setTimeout(() => {
      if (c.role === "none") this.kick(c, 1008, "idle");
    }, this.config.idleAuthTimeoutMs);
    c.idleTimer.unref();
  }
}
