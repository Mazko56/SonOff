# Sonoff Android Gateway v2.0.0 — laboratory report

Run date: 2026-11-19

## Checks performed in the current working container

- `python -m unittest discover -s tests -v`: **30 tests, PASS**.
- Actual Java sources compiled against typed lightweight Android API stubs: `Controller`, LAN state/profiles/crypto, `AndroidGatewayServer`, `EwelinkOAuth`, `LocalHttpServer`.
- OAuth URL test checks signed parameters, no app-secret leakage, region host allowlist, callback decoding and invalid-state rejection.
- Node.js syntax validation of the single bundled WebView `<script>` succeeds.
- Network simulation: encrypted LAN AES round-trip, real HTTP getState/switches/configure fixture calls to a loopback simulator, DIY plaintext fixture, mDNS advertisement structure.
- Source checks: no `/api/provision`, no `/ap` writes, no legacy cloud-replacement server; HTTPS gateway and account-authorized key storage are present.
- ZIP source includes only current tests; no stale v1.x test quarantine workaround.

## Not performed

- Android SDK `gradle assembleDebug`: **not performed**, SDK + Gradle CLI unavailable in the working container. GitHub Actions is configured to perform the real Android build and publish the APK.
- No physical Android device available: TLS Android Keystore server, foreground service and Wi-Fi binding must be verified on a real phone.
- No authenticated CoolKit developer credentials or customer eWeLink account provided: OAuth key retrieval is not tested against live servers.
- No physical Sonoff plug available: successful AES LAN state, energy telemetry, ON/OFF and simultaneous eWeLink control remain unverified.
- No 200/500-device load test; no 2000 automation scenarios; no universal unknown hardware support.

A passing source/stub test is not a guarantee of full Android build success or any particular factory firmware's compatibility.
