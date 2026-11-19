# Acceptance matrix — 2026-11-19

| Requirement (Sonoff Wi-Fi plugs phase) | Present in source | Hardware verified |
|---|---|---|
| Android-local LAN HTTPS gateway, TCP listener | Yes, port 8765 TLS | Not yet |
| WebView panel at loopback | Yes | Not yet |
| Stock Sonoff automatic NSD/mDNS discovery | Yes | Prior v1.8 logs observed 8 devices; this version not field-tested |
| Device ID, IP, port, local type, firmware/UIID if advertised or authorized | Yes | Only partial from prior logs |
| Encrypted eWeLink LAN (AES-128-CBC, per-device key) | Yes | Simulator only in this version |
| Open Sonoff DIY LAN endpoint | Yes | Simulator only |
| One-time owner-approved OAuth code flow | Yes, requires developer App ID/Secret | No real eWeLink OAuth account test |
| Enumerate accessible homes and things, fetch `devicekey` | Yes, honors returned fields | No real cloud responses tested |
| Android Keystore credential storage | Yes | Not tested on physical Android device |
| Meter telemetry (when supported) | Yes, device-profile dependent | No real meter verified |
| Multiple relays / outlet mapping | Yes, parsed state shape dependent | Simulator only |
| Relay writes after verified state | Yes | No physical ON/OFF verified |
| Hardware relay confirmation / unconfirmed state | Yes | Simulator only |
| eWeLink simultaneously usable, no re-pair | No firmware/server/Wi-Fi writes | Not yet tested on physical device |
| No hardcoded device key / no key derivation bypass | Yes | Source audit |
| Single gateway UI for devices | Yes | WebView JavaScript syntax tested |
| Long-running Android service | Yes | Android OS behavior not hardware tested |
| Unknown Sonoff models automatically controlled | **No** (requires compatible protocol/profile and authorization) | Not possible to guarantee |
| 200 / 500 concurrent real device monitoring | Not load-tested | No |
| Other TS sections (BLE, Modbus, scenarios, cameras) | Out of scope for Wi-Fi Sonoff phase | No |

## Acceptance procedure on customer hardware

1. Confirm an actual Android `GATEWAY.READY` line and network `https://PHONE_WIFI_IP:8765/v1/health` with trusted/pinned TLS certificate.
2. Confirm LAN mDNS identifies the correct `deviceid` on `192.168.x.x:8081`.
3. If encrypted, run owner-authorized OAuth import with valid developer credentials; see `OAUTH.IMPORT.COMPLETE` and actual LAN AES authentication success.
4. Confirm physical state + power/voltage/current/kWh where advertised by the selected model.
5. Send ON/OFF once at a time; confirm physical relay and `RELAY.CONFIRMED` log, not just an HTTP 202.
6. Keep official eWeLink working in parallel; never factory reset or write a new cloud target.
7. Disconnect phone internet while leaving its Wi-Fi connected **only if customer approves a test**; verify LAN still works using cached key.
8. Repeat after restart, IP reassignment and Wi-Fi roam; verify recovery and Android foreground service restrictions.

No physical device confirmation is claimed in this release.
