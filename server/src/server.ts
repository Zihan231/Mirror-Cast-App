import { readFileSync } from "node:fs";
import { createServer as createHttpServer, type IncomingMessage, type Server as HttpServer } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import type { AddressInfo } from "node:net";
import { networkInterfaces } from "node:os";
import type { Duplex } from "node:stream";
import { WebSocket, WebSocketServer } from "ws";
import type { Config } from "./config.js";
import { SignalingHub } from "./hub.js";
import type { Logger } from "./logger.js";
import { startMdns, type MdnsHandle } from "./mdns.js";
import { MemoryPairingStore, type PairingStore } from "./store.js";

export const WS_PATH = "/ws";
export const MDNS_SERVICE_TYPE = "mirrorcast";

export interface RunningServer {
  hub: SignalingHub;
  port: number;
  stop(): Promise<void>;
}

export interface ServerDeps {
  store?: PairingStore;
}

export async function startServer(config: Config, log: Logger, deps: ServerDeps = {}): Promise<RunningServer> {
  const hub = new SignalingHub(config, deps.store ?? new MemoryPairingStore(), log);
  const startedAt = Date.now();
  const connectionsByIp = new Map<string, number>();

  const handler = (req: IncomingMessage, res: import("node:http").ServerResponse): void => {
    if (req.method === "GET" && req.url === "/health") {
      void hub.stats().then((stats) => {
        res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
        res.end(JSON.stringify({ status: "ok", uptimeSeconds: Math.round((Date.now() - startedAt) / 1000), mode: config.mode, ...stats }));
      });
      return;
    }
    res.writeHead(404, { "content-type": "text/plain" });
    res.end("not found");
  };

  const http: HttpServer = config.tls
    ? createHttpsServer({ cert: readFileSync(config.tls.cert), key: readFileSync(config.tls.key), minVersion: "TLSv1.2" }, handler)
    : createHttpServer(handler);

  const wss = new WebSocketServer({ noServer: true, maxPayload: config.maxPayloadBytes, perMessageDeflate: false });

  http.on("upgrade", (req: IncomingMessage, socket: Duplex, head: Buffer) => {
    const reject = (status: number, text: string): void => {
      socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
      socket.destroy();
    };

    if (new URL(req.url ?? "/", "http://x").pathname !== WS_PATH) return reject(404, "Not Found");

    // Browsers always send Origin; the native apps never do. Default: refuse every browser origin.
    const origin = req.headers.origin;
    if (origin && !config.allowedOrigins.includes(origin)) return reject(403, "Forbidden");

    if (config.trustProxy && !config.allowInsecureWs) {
      const proto = pickHop(req.headers["x-forwarded-proto"], config.trustProxyHops);
      if (proto !== "https" && proto !== "wss") return reject(426, "Upgrade Required");
    }

    const ip = clientIp(req, config);
    if ((connectionsByIp.get(ip) ?? 0) >= config.maxConnectionsPerIp) {
      log.warn({ ip }, "connection cap reached");
      return reject(429, "Too Many Requests");
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      connectionsByIp.set(ip, (connectionsByIp.get(ip) ?? 0) + 1);
      wire(ws, ip);
    });
  });

  function wire(ws: WebSocket, ip: string): void {
    let alive = true;
    ws.on("pong", () => {
      alive = true;
    });
    const beat = setInterval(() => {
      if (!alive) {
        ws.terminate();
        return;
      }
      alive = false;
      ws.ping();
    }, config.heartbeatIntervalMs);

    const client = hub.attach(
      {
        send: (data) => {
          if (ws.readyState === WebSocket.OPEN) ws.send(data);
        },
        close: (code, reason) => ws.close(code, reason),
      },
      ip,
    );

    ws.on("message", (data, isBinary) => {
      if (isBinary) return ws.close(1003, "text only");
      client.onMessage(data.toString());
    });
    ws.on("error", (err) => log.debug({ err, ip }, "socket error"));
    ws.on("close", () => {
      clearInterval(beat);
      const n = (connectionsByIp.get(ip) ?? 1) - 1;
      if (n <= 0) connectionsByIp.delete(ip);
      else connectionsByIp.set(ip, n);
      client.onClose();
    });
  }

  await new Promise<void>((resolve, reject) => {
    http.once("error", reject);
    http.listen(config.port, config.host, () => {
      http.off("error", reject);
      resolve();
    });
  });

  const port = (http.address() as AddressInfo).port;
  if (!hub.publicUrl && config.mode === "lan") {
    const lan = firstLanAddress();
    if (lan) hub.publicUrl = `${config.tls ? "wss" : "ws"}://${lan}:${port}${WS_PATH}`;
  }
  hub.start();

  let mdns: MdnsHandle | null = null;
  if (config.mdnsEnabled) {
    mdns = startMdns({ name: config.mdnsName, type: MDNS_SERVICE_TYPE, port, path: WS_PATH, tls: Boolean(config.tls) }, log);
  }
  log.info({ port, mode: config.mode, tls: Boolean(config.tls), publicUrl: hub.publicUrl, mdns: Boolean(mdns) }, "signaling server listening");

  let stopping: Promise<void> | null = null;
  const stop = (): Promise<void> => {
    stopping ??= (async () => {
      log.info("shutting down");
      mdns?.stop();
      hub.announceShutdown();
      hub.stop();
      for (const ws of wss.clients) ws.close(1001, "server shutting down");
      await new Promise<void>((resolve) => {
        const force = setTimeout(() => {
          for (const ws of wss.clients) ws.terminate();
          resolve();
        }, 5000);
        wss.close(() => {
          clearTimeout(force);
          resolve();
        });
      });
      await new Promise<void>((resolve) => http.close(() => resolve()));
      http.closeAllConnections();
    })();
    return stopping;
  };

  return { hub, port, stop };
}

function pickHop(header: string | string[] | undefined, hops: number): string | undefined {
  if (!header) return undefined;
  const list = (Array.isArray(header) ? header.join(",") : header).split(",").map((s) => s.trim());
  return list[Math.max(0, list.length - hops)];
}

/**
 * With a trusted proxy, the address appended by *our* proxy is the one `hops`
 * from the right; anything to its left is client-controlled and ignored.
 */
export function clientIp(req: IncomingMessage, config: Pick<Config, "trustProxy" | "trustProxyHops">): string {
  const raw = (config.trustProxy ? pickHop(req.headers["x-forwarded-for"], config.trustProxyHops) : undefined) ?? req.socket.remoteAddress ?? "unknown";
  return raw.replace(/^::ffff:/, "");
}

function firstLanAddress(): string | null {
  for (const infos of Object.values(networkInterfaces())) {
    for (const i of infos ?? []) {
      if (i.family === "IPv4" && !i.internal) return i.address;
    }
  }
  return null;
}
