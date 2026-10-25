import { loadConfig } from "./config.js";
import { createLogger } from "./logger.js";
import { startServer } from "./server.js";

async function main(): Promise<void> {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    // Logger needs config for its level, so fall back to stderr here.
    console.error(err instanceof Error ? err.message : err);
    process.exit(78); // EX_CONFIG
  }

  const log = createLogger(config.logLevel);
  if (config.allowInsecureWs && !config.tls) {
    log.warn("cleartext ws:// enabled (ALLOW_INSECURE_WS); use only on a trusted LAN");
  }

  const server = await startServer(config, log);

  let shuttingDown = false;
  const shutdown = (signal: string): void => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info({ signal }, "signal received");
    const hard = setTimeout(() => process.exit(1), 10_000);
    hard.unref();
    server.stop().then(
      () => process.exit(0),
      (err) => {
        log.error({ err }, "shutdown failed");
        process.exit(1);
      },
    );
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("unhandledRejection", (err) => log.error({ err }, "unhandled rejection"));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
