# Security design

- Encrypted per-device LAN keys stored with AES-GCM using AndroidKeyStore master secret, never written to plaintext app preferences.
- The gateway bearer is random (256-bit), stored using the same encrypted Keystore scheme. The only public unauthenticated network route is `/v1/health`.
- The local UI server is bound to `127.0.0.1` and requires an Activity-only secret header on JSON routes. The OAuth App Secret is submitted to the loopback server, held only in RAM for the authorization-code flow, and never hardcoded or persisted.
- eWeLink OAuth endpoint hosts are fixed to the supported CoolKit regions (eu/us/as/cn) and TLS hostname checks remain enabled by the Android platform.
- OAuth callback URL must match the configured HTTPS redirect address and a cryptographically random state value. Tokens are held transiently and discarded after import.
- The LAN listener binds only to an active private Wi-Fi IPv4 interface. When that interface changes, listening sockets and clients close.
- WebSocket is WSS only, enforces masked frames and size limits, and has a bounded connection pool. It requires the bearer in the HTTPS Authorization header or in the secure WebSocket query.
- Relay writes are rejected until real device state is authenticated. HTTP 202 means queued, **not** physical action confirmed.
- Do not expose port 8765 via router port forwarding. For LAN clients, pin the real self-signed certificate fingerprint displayed on the owner phone, never simply disable certificate validation in production.
- Multi-user/OEM production distribution needs a backend to keep the OAuth App Secret confidential. Users must consent to cloud key import, and OAuth permissions/region may restrict device access.
