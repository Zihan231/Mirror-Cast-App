import { Bonjour } from "bonjour-service";
import type { Logger } from "./logger.js";

export interface MdnsOptions {
  name: string;
  /** Service type without underscores, e.g. "mirrorcast" → _mirrorcast._tcp */
  type: string;
  port: number;
  path: string;
  tls: boolean;
}

export interface MdnsHandle {
  stop(): void;
}

/**
 * Advertises the signaling endpoint on the LAN. TXT records: v (protocol),
 * path (WebSocket path), tls ("1" = wss, "0" = ws).
 */
export function startMdns(opts: MdnsOptions, log: Logger): MdnsHandle {
  const bonjour = new Bonjour();
  const service = bonjour.publish({
    name: opts.name,
    type: opts.type,
    protocol: "tcp",
    port: opts.port,
    txt: { v: "1", path: opts.path, tls: opts.tls ? "1" : "0" },
  });
  service.on("error", (err: unknown) => log.warn({ err }, "mdns error"));
  log.info({ type: `_${opts.type}._tcp`, name: opts.name, port: opts.port }, "mdns advertising");

  return {
    stop() {
      try {
        bonjour.unpublishAll(() => bonjour.destroy());
      } catch (err) {
        log.warn({ err }, "mdns shutdown failed");
      }
    },
  };
}
