import { timingSafeEqual } from "node:crypto";

/**
 * Pairing records are what a Redis adapter would hold. Live sockets and
 * sessions stay in the hub's memory (a multi-instance deployment needs sticky
 * routing or pub/sub on top of this; out of scope for now).
 *
 * The plaintext code is never stored: `codeHash` is HMAC-SHA256(secret, code).
 */
export interface PairingRecord {
  pairingId: string;
  codeHash: string; // hex
  receiverId: string;
  createdAt: number;
  expiresAt: number;
}

export interface PairingStore {
  /** Returns false when a live record with the same codeHash already exists. */
  add(record: PairingRecord): Promise<boolean>;
  /** Atomic get-and-delete. Returns null when absent or expired. Single use by construction. */
  take(codeHash: string, now: number): Promise<PairingRecord | null>;
  removeByReceiver(receiverId: string): Promise<PairingRecord | null>;
  /** Removes and returns all records with expiresAt <= now. */
  sweepExpired(now: number): Promise<PairingRecord[]>;
  size(): Promise<number>;
}

export class MemoryPairingStore implements PairingStore {
  private readonly byHash = new Map<string, PairingRecord>();
  private readonly byReceiver = new Map<string, string>();

  async add(record: PairingRecord): Promise<boolean> {
    if (this.byHash.has(record.codeHash)) return false;
    this.byHash.set(record.codeHash, record);
    this.byReceiver.set(record.receiverId, record.codeHash);
    return true;
  }

  async take(codeHash: string, now: number): Promise<PairingRecord | null> {
    const rec = this.byHash.get(codeHash);
    if (!rec) return null;
    // Map lookup is keyed by an HMAC, so lookup timing reveals nothing about the code.
    // The explicit compare keeps the equality check constant-time regardless.
    const a = Buffer.from(rec.codeHash, "hex");
    const b = Buffer.from(codeHash, "hex");
    if (a.length !== b.length || !timingSafeEqual(a, b)) return null;
    this.delete(rec);
    return rec.expiresAt > now ? rec : null;
  }

  async removeByReceiver(receiverId: string): Promise<PairingRecord | null> {
    const hash = this.byReceiver.get(receiverId);
    const rec = hash ? this.byHash.get(hash) : undefined;
    if (!rec) return null;
    this.delete(rec);
    return rec;
  }

  async sweepExpired(now: number): Promise<PairingRecord[]> {
    const expired: PairingRecord[] = [];
    for (const rec of this.byHash.values()) {
      if (rec.expiresAt <= now) expired.push(rec);
    }
    for (const rec of expired) this.delete(rec);
    return expired;
  }

  async size(): Promise<number> {
    return this.byHash.size;
  }

  private delete(rec: PairingRecord): void {
    this.byHash.delete(rec.codeHash);
    if (this.byReceiver.get(rec.receiverId) === rec.codeHash) this.byReceiver.delete(rec.receiverId);
  }
}
