import { pino, type Logger } from "pino";

export type { Logger };

export function createLogger(level: string): Logger {
  return pino({
    level,
    base: undefined,
    timestamp: pino.stdTimeFunctions.isoTime,
    // Pairing codes, SDP and TURN credentials never belong in logs.
    redact: {
      paths: ["code", "sdp", "candidate", "credential", "*.code", "*.sdp", "*.candidate", "*.credential"],
      censor: "[redacted]",
    },
  });
}
