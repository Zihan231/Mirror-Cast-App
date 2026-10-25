# Signaling server

Node 20 + TypeScript. Protocol: [../docs/PROTOCOL.md](../docs/PROTOCOL.md).

```bash
npm ci
npm test                 # vitest integration tests (real WebSocket server on an ephemeral port)
npm run build

# LAN mode (no internet needed): plain ws:// + mDNS advertisement
npm run start:lan        # POSIX shell; on Windows: $env:MODE='lan'; $env:ALLOW_INSECURE_WS='true'; npm start

# Manual end-to-end with the CLI client
npm run client -- receiver ws://localhost:8080/ws --auto-approve    # prints the code
npm run client -- sender   ws://localhost:8080/ws 123456
```

The server refuses to start unless one of `TLS_CERT_FILE`+`TLS_KEY_FILE`, `TRUST_PROXY=true`, or `ALLOW_INSECURE_WS=true` is set.
All settings are in [.env.example](.env.example). Cloud deployment: [../deploy/](../deploy/).

Verify mDNS from another machine: `dns-sd -B _mirrorcast._tcp` (macOS) or `avahi-browse -rt _mirrorcast._tcp` (Linux).
