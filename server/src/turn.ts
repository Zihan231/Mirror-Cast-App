import { createHmac, randomBytes } from "node:crypto";
import type { Config } from "./config.js";
import type { IceServer } from "./protocol.js";

/**
 * Builds ICE servers. TURN credentials follow coturn's `use-auth-secret`
 * (TURN REST API): username = "<unix expiry>:<tag>", credential = base64(HMAC-SHA1(secret, username)).
 * Credentials are minted per request and never logged.
 */
export function buildIceServers(config: Pick<Config, "stunUrls" | "turnUrls" | "turnSecret" | "turnTtlSeconds">, nowMs: number): IceServer[] {
  const servers: IceServer[] = [];
  if (config.stunUrls.length > 0) servers.push({ urls: config.stunUrls });
  if (config.turnUrls.length > 0 && config.turnSecret) {
    const expiry = Math.floor(nowMs / 1000) + config.turnTtlSeconds;
    const username = `${expiry}:${randomBytes(6).toString("hex")}`;
    const credential = createHmac("sha1", config.turnSecret).update(username).digest("base64");
    servers.push({ urls: config.turnUrls, username, credential });
  }
  return servers;
}
