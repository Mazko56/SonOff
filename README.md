# Sonoff Android Gateway — clean source v2.0.0

Standalone Android app. One local gateway server, one user interface, stock-firmware Sonoff LAN adapters, explicit owner-authorized eWeLink OAuth key import. Designed not to reset Wi-Fi or replace the eWeLink cloud endpoint of installed devices.

**Status: source candidate, not a hardware-certified production APK.** New Java modules passed source/stub checks and the protocol simulator suite, but no Android SDK build and no real Sonoff hardware test were possible in this environment. Compatibility with *all* Sonoff models is **not** claimed.

## Architecture

```text
Android phone / foreground service
  ├── LAN discovery: Android NSD + multicast mDNS (_ewelink._tcp)
  ├── Stock Sonoff protocol adapters: encrypted AES-CBC / DIY plaintext
  ├── Android Keystore: per-device secret keys and gateway bearer
  ├── HTTPS gateway at https://PHONE_WIFI_IP:8765
  │    ├── GET  /v1/health (no credentials)
  │    ├── GET  /v1/devices (Bearer)
  │    ├── POST /v1/command (Bearer)
  │    └── WSS  /v1/ws?token=...  (state snapshots + validated relay commands)
  └── Loopback WebView UI at http://127.0.0.1:randomPort
       └── OAuth login once -> /v2/user/oauth/token -> /v2/family -> /v2/device/thing
                                  ↓ import devicekey to Android Keystore
Sonoff factory LAN: 192.168.x.x:8081/zeroconf/* (no changes to original provisioning)
```

This is **not** a Sonoff cloud emulator and does not try to make factory devices connect to the phone instead of eWeLink. Android is the gateway for local controllers, applications and dashboards; the app makes the authorized `:8081` connection to the Sonoff device.

## User setup

1. Install the APK produced by GitHub Actions and connect the phone to the same home Wi-Fi as Sonoff devices.
2. The app discovers compatible devices automatically. Open **Help → Доступ eWeLink** if the factory LAN protocol is encrypted and no key is cached.
3. For owner-authorized OAuth you need **your own eWeLink developer App ID and App Secret**, with its registered HTTPS redirect URL (example `https://127.0.0.1/callback` if permitted by CoolKit's developer portal). Choose the correct account region.
4. Tap the authorization button and sign in **on eWeLink's browser page**. Copy the final redirect URL from the address bar and paste into the app; tap **Імпортувати**. The application validates `state` and the exact registered redirect URL.
5. Available device keys are stored encrypted in Android Keystore. The developer secret, OAuth access and refresh tokens are **not persisted**. Device control and telemetry are local thereafter, using factory encryption and verified responses. eWeLink may remain installed and usable. The account must permit returning the devices and their `devicekey` fields.
6. Under Help → Local server see the HTTPS address, port and owner bearer token. Do not disclose the bearer token publicly.

### Important OEM/API restrictions

The eWeLink developer App ID is supplied externally. This APK contains no shared App Secret or fake key derivation. If delivering the app to arbitrary end users, **move the confidential OAuth App Secret to your own protected backend**; a client secret entered or stored permanently in a distributed mobile APK is not a production-grade multi-user authorization design. OAuth access depends on developer permissions, account permissions and real CoolKit API responses.

The **HTTPS server uses a self-signed AndroidKeyStore certificate**, not a public CA certificate. Remote API clients must verify its actual fingerprint using a trusted out-of-band channel before pinning/accepting it. Do not disable TLS verification in production clients. Network ports must not be exposed to the internet.

## Server API

A trusted LAN client can make requests to `https://PHONE_IP:8765` using `Authorization: Bearer <gateway token>` and the trusted certificate.

`GET /v1/health` → `{ "ok": true, "service": "sonoff-lan-gateway", "port": 8765 }`.

`GET /v1/devices` → JSON containing `devices` with only public state/telemetry fields and an `authenticated` and `canControl` flag; does not return the key.

`POST /v1/command`, `Content-Type: application/json` → `{ "deviceid": "10013bec50", "on": true, "outlet": 0 }`. Successful enqueue returns HTTP 202 `{ "queued": true, "confirmed": false }`; physical confirmation arrives later in an updated device state.

`wss://PHONE_IP:8765/v1/ws?token=<gateway token>` supports state snapshots (one JSON object per message). Optionally send `{"action":"relay","deviceid":"...","on":true,"outlet":0}`. Browser WS clients cannot send Authorization headers; this is why the WebSocket route alone accepts a token via the **WSS** query parameter. Keep it out of logs and URLs shared with third parties.

## Build

GitHub Actions: `.github/workflows/build-apk.yml` installs JDK 17, Android SDK 35, Gradle 8.9, Node 22 and Python 3.12, runs tests, then `gradle assembleDebug` and uploads `app-debug.apk`.

Manual build after installing JDK 17, Gradle 8.9, Android SDK platform/build tools 35:

```bash
python -m pip install cryptography
python -m unittest discover -s tests -v
gradle --no-daemon assembleDebug
```

### Replace an older local Git checkout safely (PowerShell)

Extract this ZIP to a **new directory**, e.g. `C:\SonoffGatewayNew`. Make sure `C:\sonoff` has no uncommitted changes. Then from the extracted directory:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\replace_repo_clean.ps1 -Repo C:\sonoff -Source C:\SonoffGatewayNew
cd C:\sonoff
py -m unittest discover -s tests -v
git status --short
git add -A
git commit -m "Rebuild clean Sonoff Android Gateway v2.0"
git push origin main
```

The script backs up managed project folders **outside** the Git repository before removing old files; it does not alter `.git`, production devices or credentials. Do not run it if the repository contains uncommitted changes.

## Scope against original technical specification

- Implemented for this **Sonoff Wi-Fi plug** development phase: local Wi-Fi discovery (NSD/mDNS), encrypted and open stock LAN communication where supported, safe state read, verified relay writes, energy telemetry when exposed by device, per-device encrypted key storage, owner-authorized key retrieval, local HTTPS REST and WebSocket hub, Android foreground service, diagnostics and auto rediscovery.
- Not implemented in this phase: generic Modbus TCP, Bluetooth Classic, BLE GATT, SSDP, RTSP/ONVIF, generic UDP reverse-connect for arbitrary equipment, 2000 automation scenarios, 200/500 physical device load tests, all-model universal Sonoff support, commercial OAuth backend.

See `docs/ACCEPTANCE_MATRIX.md` and `docs/SECURITY.md`.

## Source references (protocol/architecture study)

- https://github.com/plugpogpag/ewelink-lan-agent — local agent REST/WebSocket concept
- https://github.com/AlexxIT/SonoffLAN — stock firmware LAN protocol, UIIDs and model-specific payloads
- https://github.com/AlanSRU/ioBroker.ewelink — OAuth 2.0 owner-authorized key acquisition and local encrypted control
- https://github.com/CoolKit-Technologies/eWeLink-API/blob/main/en/APICenterV2.md — authorized thing list/families and `devicekey`
- https://github.com/nocmt/ewelink-api-next/blob/main/src/web/apis/oauth/createLoginUrl.ts — OAuth login URL signature structure
- https://github.com/nocmt/ewelink-api-next/blob/main/src/web/apis/oauth/getToken.ts — OAuth authorization-code token exchange

The gateway and OAuth modules in this source are independent Java implementations modeled on documented protocol behavior; this package does **not** include entire third-party libraries or third-party secret credentials.
