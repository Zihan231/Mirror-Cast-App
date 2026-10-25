import { z } from "zod";

export const PROTOCOL_VERSION = 1;
export const CODE_REGEX = /^\d{6}$/;

const reqId = z.string().min(1).max(64).optional();
// Device names are rendered on a TV; control characters have no business there.
const deviceName = z
  .string()
  .trim()
  .min(1)
  .max(64)
  // eslint-disable-next-line no-control-regex
  .refine((s) => !/[\u0000-\u001f\u007f]/.test(s), "invalid characters");

/** Messages a client may send. Strict: unknown keys are rejected. */
export const ClientMessageSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("register-receiver"), id: reqId, deviceName, protocolVersion: z.literal(PROTOCOL_VERSION) }).strict(),
  z.object({ type: z.literal("create-pairing"), id: reqId }).strict(),
  z
    .object({ type: z.literal("join-pairing"), id: reqId, code: z.string().regex(CODE_REGEX), deviceName, protocolVersion: z.literal(PROTOCOL_VERSION) })
    .strict(),
  z.object({ type: z.literal("pairing-decision"), id: reqId, pairingId: z.string().uuid(), approve: z.boolean() }).strict(),
  z.object({ type: z.literal("offer"), id: reqId, sdp: z.string().min(1).max(60_000) }).strict(),
  z.object({ type: z.literal("answer"), id: reqId, sdp: z.string().min(1).max(60_000) }).strict(),
  z
    .object({
      type: z.literal("ice-candidate"),
      id: reqId,
      // Empty string = end-of-candidates marker.
      candidate: z.string().max(2000),
      sdpMid: z.string().max(64).nullish(),
      sdpMLineIndex: z.number().int().min(0).max(255).nullish(),
    })
    .strict(),
  z.object({ type: z.literal("get-ice-servers"), id: reqId }).strict(),
  z.object({ type: z.literal("bye"), id: reqId, reason: z.string().max(64).optional() }).strict(),
  z.object({ type: z.literal("ping"), id: reqId, ts: z.number().optional() }).strict(),
]);

export type ClientMessage = z.infer<typeof ClientMessageSchema>;
export type ClientMessageOf<T extends ClientMessage["type"]> = Extract<ClientMessage, { type: T }>;

export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

export type ErrorCode =
  | "invalid-message"
  | "wrong-state"
  | "invalid-code"
  | "rate-limited"
  | "busy"
  | "receiver-unavailable"
  | "not-found"
  | "forbidden";

export type DenyReason = "rejected" | "timeout" | "receiver-gone";

export type ServerMessage =
  | { type: "registered"; id?: string; receiverId: string; serverTime: number; protocolVersion: number }
  | { type: "pairing-created"; id?: string; pairingId: string; code: string; expiresAt: number; ttlSeconds: number; qrPayload: string | null }
  | { type: "pairing-expired"; pairingId: string }
  | { type: "pairing-pending"; id?: string; pairingId: string; approvalTimeoutMs: number }
  | { type: "pairing-request"; pairingId: string; senderName: string; approvalTimeoutMs: number }
  | { type: "pairing-denied"; pairingId: string; reason: DenyReason }
  | { type: "paired"; sessionId: string; role: "sender" | "receiver"; peerName: string; iceServers: IceServer[] }
  | { type: "ice-servers"; id?: string; iceServers: IceServer[] }
  | { type: "offer"; sdp: string }
  | { type: "answer"; sdp: string }
  | { type: "ice-candidate"; candidate: string; sdpMid?: string | null; sdpMLineIndex?: number | null }
  | { type: "bye"; reason: string }
  | { type: "pong"; id?: string; ts?: number; serverTime: number }
  | { type: "error"; id?: string; code: ErrorCode; message: string; retryAfterMs?: number };
