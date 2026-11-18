# Verification report — 2026-11-18

| Check | Result | What it actually proves |
|---|---|---|
| Python cryptography roundtrip for LAN AES-CBC and negative wrong-key test | PASS | Our fake protocol supports encryption/decryption roundtrip |
| Python local fake S60 POST /zeroconf/switches -> on, then /zeroconf/getState | PASS | Simulated service correctly changes and reports state |
| Python fake mDNS packet with no A record | PASS | Test fixture emits PTR, SRV, encrypted TXT and optional missing-A case |
| Java MdnsPacket parser on no-A TXT+SRV packet from Python | PASS | Java parser handles fake advertised encrypted mDNS state and source fallback can be attempted |
| Java sources compiled against local lightweight Android API stubs (not Android SDK) | PASS SYNTAX ONLY | Does not replace real Android Gradle compilation or verify Android API compatibility |
| Browser preview UI inline JavaScript syntax and simulated relay ON using headless Chromium | PASS | UI renders and transitions in the pure browser mock |
| Android APK assembled and installed | NOT RUN | Android SDK/Gradle unavailable in authoring container |
| Physical Sonoff S60TPF discovery and control | NOT RUN | Physical device not available |
| eWeLink co-existence and zero firmware impact | NOT RUN | Needs verification with real S60TPF |
| Plug-initiated connection to phone-hosted server | NOT IMPLEMENTED | Stock LAN protocol is in opposite direction; not confirmed supported by firmware |
| Automatic encrypted control without devicekey/onboarding | NOT IMPLEMENTED | Would bypass per-device encryption authentication; requires authorized key retrieval |

Tests and code are prototypes; no hardware or production guarantee is implied.
