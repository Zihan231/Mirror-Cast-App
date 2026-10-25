export type Clock = () => number;

interface FailureEntry {
  count: number;
  windowStart: number;
  blockedUntil: number;
}

/** Counts failures per key; after `max` within `windowMs` the key is blocked for `blockMs`. */
export class FailureLimiter {
  private readonly entries = new Map<string, FailureEntry>();

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly blockMs: number,
    private readonly now: Clock = Date.now,
  ) {}

  /** Returns 0 when allowed, otherwise the number of ms until the block lifts. */
  retryAfterMs(key: string): number {
    const e = this.entries.get(key);
    if (!e) return 0;
    return Math.max(0, e.blockedUntil - this.now());
  }

  recordFailure(key: string): void {
    const t = this.now();
    let e = this.entries.get(key);
    if (!e || t - e.windowStart > this.windowMs) {
      e = { count: 0, windowStart: t, blockedUntil: e?.blockedUntil ?? 0 };
      this.entries.set(key, e);
    }
    e.count += 1;
    if (e.count >= this.max) {
      e.blockedUntil = t + this.blockMs;
      e.count = 0;
      e.windowStart = t;
    }
  }

  sweep(): void {
    const t = this.now();
    for (const [k, e] of this.entries) {
      if (e.blockedUntil <= t && t - e.windowStart > this.windowMs) this.entries.delete(k);
    }
  }
}

/** Fixed-window counter: `hit` returns false once `max` hits happened in the current window. */
export class WindowCounter {
  private readonly entries = new Map<string, { count: number; windowStart: number }>();

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly now: Clock = Date.now,
  ) {}

  hit(key: string): boolean {
    const t = this.now();
    let e = this.entries.get(key);
    if (!e || t - e.windowStart >= this.windowMs) {
      e = { count: 0, windowStart: t };
      this.entries.set(key, e);
    }
    if (e.count >= this.max) return false;
    e.count += 1;
    return true;
  }

  sweep(): void {
    const t = this.now();
    for (const [k, e] of this.entries) if (t - e.windowStart >= this.windowMs) this.entries.delete(k);
  }
}

/** Per-connection message throttle. */
export class TokenBucket {
  private tokens: number;
  private last: number;

  constructor(
    private readonly capacity: number,
    private readonly refillPerSec: number,
    private readonly now: Clock = Date.now,
  ) {
    this.tokens = capacity;
    this.last = now();
  }

  take(): boolean {
    const t = this.now();
    this.tokens = Math.min(this.capacity, this.tokens + ((t - this.last) / 1000) * this.refillPerSec);
    this.last = t;
    if (this.tokens < 1) return false;
    this.tokens -= 1;
    return true;
  }
}
