import { pino } from "pino";
import { loadConfig } from "../src/config.js";
import { startServer, type RunningServer } from "../src/server.js";
import { TestClient, type Msg } from "../tools/test-client.js";

export interface Harness {
  server: RunningServer;
  url: string;
  httpUrl: string;
  clients: TestClient[];
  connect(opts?: { origin?: string; headers?: Record<string, string> }): Promise<TestClient>;
  close(): Promise<void>;
}

export async function startHarness(env: Record<string, string> = {}): Promise<Harness> {
  const config = loadConfig({
    ALLOW_INSECURE_WS: "true",
    HOST: "127.0.0.1",
    PORT: "0",
    SWEEP_INTERVAL_MS: "20",
    LOG_LEVEL: "silent",
    ...env,
  });
  const server = await startServer(config, pino({ level: "silent" }));
  const clients: TestClient[] = [];
  const url = `ws://127.0.0.1:${server.port}/ws`;
  return {
    server,
    url,
    httpUrl: `http://127.0.0.1:${server.port}`,
    clients,
    async connect(opts) {
      const c = await TestClient.connect(url, opts);
      clients.push(c);
      return c;
    },
    async close() {
      for (const c of clients) c.close();
      await server.stop();
    },
  };
}

export async function registerReceiver(h: Harness, name = "Living Room TV"): Promise<{ tv: TestClient; code: string; pairingId: string }> {
  const tv = await h.connect();
  const reg = await tv.request({ type: "register-receiver", deviceName: name, protocolVersion: 1 });
  if (reg.type !== "registered") throw new Error(`register failed: ${JSON.stringify(reg)}`);
  const created = await tv.request({ type: "create-pairing" });
  if (created.type !== "pairing-created") throw new Error(`create failed: ${JSON.stringify(created)}`);
  return { tv, code: created.code as string, pairingId: created.pairingId as string };
}

/** Receiver + sender paired and approved. */
export async function pairedSession(h: Harness): Promise<{ tv: TestClient; phone: TestClient; sessionId: string }> {
  const { tv, code } = await registerReceiver(h);
  const phone = await h.connect();
  phone.send({ type: "join-pairing", code, deviceName: "Pixel", protocolVersion: 1 });
  await phone.next("pairing-pending");
  const req = await tv.next("pairing-request");
  tv.send({ type: "pairing-decision", pairingId: req.pairingId, approve: true });
  const [a, b] = await Promise.all([phone.next("paired"), tv.next("paired")]);
  if (a.sessionId !== b.sessionId) throw new Error("session mismatch");
  return { tv, phone, sessionId: a.sessionId as string };
}

export type { Msg };
export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));
