import { z } from "zod";

const bool = z.enum(["true", "false", "1", "0"]).transform((v) => v === "true" || v === "1");

const csv = z.string().transform((v) =>
  v
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean),
);

const int = (min: number, max: number) => z.coerce.number().int().min(min).max(max);

const EnvSchema = z.object({
  MODE: z.enum(["cloud", "lan"]).default("cloud"),
  HOST: z.string().default("0.0.0.0"),
  PORT: int(0, 65535).default(8080),
  LOG_LEVEL: z.enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"]).default("info"),

  TLS_CERT_FILE: z.string().optional(),
  TLS_KEY_FILE: z.string().optional(),
  TRUST_PROXY: bool.default("false"),
  TRUST_PROXY_HOPS: int(1, 5).default(1),
  ALLOW_INSECURE_WS: bool.default("false"),
  PUBLIC_URL: z.string().url().optional(),
  ALLOWED_ORIGINS: csv.default(""),

  PAIRING_TTL_SECONDS: int(1, 3600).default(300),
  APPROVAL_TIMEOUT_SECONDS: int(1, 600).default(60),
  JOIN_MAX_FAILURES: int(1, 100).default(5),
  JOIN_FAILURE_WINDOW_SECONDS: int(1, 3600).default(60),
  JOIN_BLOCK_SECONDS: int(1, 86400).default(300),
  CREATE_MAX_PER_MINUTE: int(1, 600).default(10),
  MAX_ACTIVE_PAIRINGS: int(1, 1_000_000).default(10_000),
  PAIRING_SECRET: z.string().min(16).optional(),
  SWEEP_INTERVAL_MS: int(10, 60_000).default(1000),

  MAX_CONNECTIONS_PER_IP: int(1, 10_000).default(20),
  HEARTBEAT_INTERVAL_SECONDS: int(1, 300).default(15),
  IDLE_AUTH_TIMEOUT_SECONDS: int(1, 600).default(30),
  MAX_PAYLOAD_BYTES: int(1024, 1_048_576).default(65_536),

  STUN_URLS: csv.default(""),
  TURN_URLS: csv.default(""),
  TURN_SECRET: z.string().min(16).optional(),
  TURN_TTL_SECONDS: int(60, 604_800).default(21_600),

  ENABLE_TEST_PAGE: bool.optional(),
  MDNS_ENABLED: bool.optional(),
  MDNS_NAME: z.string().min(1).max(63).default("Mirror Signaling"),
});

export interface Config {
  mode: "cloud" | "lan";
  host: string;
  port: number;
  logLevel: string;
  tls: { cert: string; key: string } | null;
  trustProxy: boolean;
  trustProxyHops: number;
  allowInsecureWs: boolean;
  publicUrl: string | null;
  allowedOrigins: string[];
  pairingTtlMs: number;
  approvalTimeoutMs: number;
  joinMaxFailures: number;
  joinFailureWindowMs: number;
  joinBlockMs: number;
  createMaxPerMinute: number;
  maxActivePairings: number;
  pairingSecret: string | null;
  sweepIntervalMs: number;
  maxConnectionsPerIp: number;
  heartbeatIntervalMs: number;
  idleAuthTimeoutMs: number;
  maxPayloadBytes: number;
  stunUrls: string[];
  turnUrls: string[];
  turnSecret: string | null;
  turnTtlSeconds: number;
  /** Serves /test, a browser receiver for development. Defaults to on in LAN mode only. */
  testPage: boolean;
  mdnsEnabled: boolean;
  mdnsName: string;
}

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  // Treat empty strings as unset so .env files with blank values behave.
  const cleaned = Object.fromEntries(Object.entries(env).filter(([, v]) => v !== undefined && v !== ""));
  const e = EnvSchema.parse(cleaned);

  if (Boolean(e.TLS_CERT_FILE) !== Boolean(e.TLS_KEY_FILE)) {
    throw new Error("TLS_CERT_FILE and TLS_KEY_FILE must be set together");
  }
  const tls = e.TLS_CERT_FILE && e.TLS_KEY_FILE ? { cert: e.TLS_CERT_FILE, key: e.TLS_KEY_FILE } : null;

  if (!tls && !e.TRUST_PROXY && !e.ALLOW_INSECURE_WS) {
    throw new Error(
      "Refusing to start without transport security: set TLS_CERT_FILE/TLS_KEY_FILE, " +
        "or TRUST_PROXY=true behind a TLS-terminating proxy, or ALLOW_INSECURE_WS=true (LAN only).",
    );
  }
  if (e.TURN_URLS.length > 0 && !e.TURN_SECRET) {
    throw new Error("TURN_URLS is set but TURN_SECRET is missing");
  }

  return {
    mode: e.MODE,
    host: e.HOST,
    port: e.PORT,
    logLevel: e.LOG_LEVEL,
    tls,
    trustProxy: e.TRUST_PROXY,
    trustProxyHops: e.TRUST_PROXY_HOPS,
    allowInsecureWs: e.ALLOW_INSECURE_WS,
    publicUrl: e.PUBLIC_URL ?? null,
    allowedOrigins: e.ALLOWED_ORIGINS,
    pairingTtlMs: e.PAIRING_TTL_SECONDS * 1000,
    approvalTimeoutMs: e.APPROVAL_TIMEOUT_SECONDS * 1000,
    joinMaxFailures: e.JOIN_MAX_FAILURES,
    joinFailureWindowMs: e.JOIN_FAILURE_WINDOW_SECONDS * 1000,
    joinBlockMs: e.JOIN_BLOCK_SECONDS * 1000,
    createMaxPerMinute: e.CREATE_MAX_PER_MINUTE,
    maxActivePairings: e.MAX_ACTIVE_PAIRINGS,
    pairingSecret: e.PAIRING_SECRET ?? null,
    sweepIntervalMs: e.SWEEP_INTERVAL_MS,
    maxConnectionsPerIp: e.MAX_CONNECTIONS_PER_IP,
    heartbeatIntervalMs: e.HEARTBEAT_INTERVAL_SECONDS * 1000,
    idleAuthTimeoutMs: e.IDLE_AUTH_TIMEOUT_SECONDS * 1000,
    maxPayloadBytes: e.MAX_PAYLOAD_BYTES,
    stunUrls: e.STUN_URLS,
    turnUrls: e.TURN_URLS,
    turnSecret: e.TURN_SECRET ?? null,
    turnTtlSeconds: e.TURN_TTL_SECONDS,
    testPage: e.ENABLE_TEST_PAGE ?? e.MODE === "lan",
    mdnsEnabled: e.MDNS_ENABLED ?? e.MODE === "lan",
    mdnsName: e.MDNS_NAME,
  };
}
