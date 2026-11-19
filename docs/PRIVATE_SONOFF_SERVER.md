# Sonoff Private LAN Server — experimental adapter v1.7.0

**Status: LAB / UNQUALIFIED, NOT A PRODUCTION-SUPPORTED SONOFF FIRMWARE.**

This is an independent Android Java implementation of the **historic, device-initiated Sonoff private-server protocol** researched from:
- https://github.com/SideCodeIO/Sonoff-Server — device dispatch, WSS `/api/ws`, register/date/update/query, multi-channel switches; README describes testing on older Sonoff Dual.
- https://github.com/mdopp/simple-sonoff-server — private Sonoff server and old AP `/ap` provisioning. **Its README states the server is discontinued due to certificate validation on newer Sonoff firmware.**
- https://github.com/vponomarev/Sonoff-Server — user-uploaded historical PHP server ZIP; **README explicitly says the manufacturer added SSL certificate pinning, so this server stopped working with new firmware.** No bundled old TLS keys or certificates are incorporated.
- https://developer.android.com/develop/connectivity/wifi/wifi-scan — asynchronous Wi-Fi scan callback.
- https://developer.android.com/develop/background-work/services/fgs/service-types — connected-device foreground-service requirements.

## Architecture

- `LocalHttpServer` remains **loopback-only** for WebView UI on `127.0.0.1:<random>`; token authentication is unchanged.
- `LegacyPrivateServer` starts a **separate HTTPS + WSS listener on the current private Wi-Fi IPv4**, HTTPS port **1081** and WSS port **8090**, using a non-exportable RSA key in Android Keystore and a unique self-signed certificate generated on that phone.
- `GET https://PHONE_IP:1081/health` returns only `ok`, protocol tag, and count of connected devices. Browsers/curl will flag the self-signed certificate; for an isolated test only, `curl.exe -k https://PHONE_IP:1081/health` can check the TCP/TLS server without receiving device secrets. Do **not** expose port 1081 through the router or Internet.
- `POST /dispatch/device` authenticates an existing locally stored per-device `apikey`; only then returns the private server IP and WebSocket port **8090**. The successful dispatch also creates a 90-second single-use IP/DeviceID-bound proof for old clients using a distinct WebSocket key.
- A device establishes WSS `/api/ws` or `/`, upgrades to WebSocket, sends `action=register` with valid device ID + factory key, receives a random **ephemeral** session token. Subsequent actions require the token. `update` provides a real state; `query` only returns allowlisted state fields.
- ON/OFF WebSocket messages are sent only to **authenticated active sessions with a previously reported valid relay state**. A sent frame is not reported as confirmed state. Confirmation comes from a subsequent `update` reporting the desired channel.
- Multi-channel `switches[]` appears as separate channel controls with `outlet` numbers observed in the state, not hardcoded from an advertised model name.
- `SonoffForegroundService` keeps the runtime alive when the WebView/Activity is destroyed; Android OS may still stop a service.
- Existing mDNS direct LAN (AlexxIT/Urban82), DIY, TLS-less LAN AES and passive Matter discovery remain independent.

## Explicit legacy AP provisioning

This potentially reconfigures the device. It is **never triggered by passive scans**.

1. Test only on a plug you own / may re-pair, with a recovery plan. Do **not** modify a customer's installed device merely for diagnostics.
2. Enable Android Nearby Wi-Fi/Location permissions and Location toggle if required.
3. Confirm that the journal contains `PRIVATE.SERVER.READY` with Wi-Fi IP and port 1081. It may fail if AndroidKeyStore TLS setup is unsupported; inspect `PRIVATE.SERVER.UNAVAILABLE`.
4. Put **one** proven-compatible older Sonoff into legacy `ITEAD-...` SoftAP mode.
5. In existing app **Допомога** → «Підключення через власний сервер», enter **home Wi-Fi SSID/password**, expressly acknowledge that the target will be reconfigured, and start. The password is kept in memory until the attempt finishes; it is **not** saved or logged.
6. After Android OS authorizes `WifiNetworkSpecifier`, the app validates the `10.10.7.x` subnet and GETs `/device`. It verifies the exact device ID against the selected hotspot, stores its key encrypted with Android Keystore, and **only following explicit consent** POSTs `/ap` the local server IP and home Wi-Fi credentials.
7. The device returns to the home Wi-Fi. The app restarts its server listener as the home connection becomes active. Device firmware must support the legacy unpinned TLS certificate and connect to `/dispatch/device` + `/api/ws`. A successful `/ap` response is **NOT** proof of registration, metrics or relay control.
8. If it connects, expect `PRIVATE.DISPATCH.ACCEPTED`, `PRIVATE.WS.REGISTER.OK`, `PRIVATE.WS.UPDATE`, and a real `RELAY.CONFIRMED` after ON/OFF. If there is a `PRIVATE.TLS.REJECTED`, model compatibility is unverified and certificate pinning may be the reason.

**Important:** The legacy ITEAD AP has a widely known default WPA2 passphrase; provisioning sends home Wi-Fi credentials to this local AP over HTTP. Only perform in a physically trusted environment. Changing the server may disconnect the device from its prior eWeLink setup, and reversing this requires re-pairing. Private phone IP can change with DHCP; stable phone LAN IP is needed for device-initiated connections.

## Definitive limitations

- **No evidence of compatibility with stock Sonoff firmware 3.6.4.** Legacy private servers have failed for far older pinned firmware. An arbitrary self-signed certificate is not a substitute for the manufacturer-pinned certificate. We do not provide stolen certificates, a TLS bypass or false success states.
- The supplied Dual R2 screenshot does not identify the customer's device `10013bec50` (`enhanced_plug`) nor the other seven mDNS discoveries. It shows a client UI, not the protocol between device and controller.
- A universal app for **all** Sonoff, Zigbee, Matter, Tuya, MQTT and every unknown converter is not demonstrated. Zigbee requires a coordinator; Matter commissioning requires an appropriate controller and credentials.
- No physical Android phone / commercial Sonoff connected to this execution environment. Java class compilation tests use Android API stubs and do not replace `gradle assembleDebug` or hardware acceptance.

## Verification status

`python -m unittest discover -s tests -v`: 34 tests pass; includes previous LAN simulator scenarios, legacy SoftAP parser, Java source protocol tests, and stub-compilation of new HTTPS/WebSocket server + foreground service. `node --check` on the embedded UI script also passes. No real TLS handshake against AndroidKeyStore or stock hardware was performed; these need Android device and a compatible legacy Sonoff. **DO NOT describe this as a fully validated production APK or claim 100% device compatibility.**

## Version 1.7.0 compatibility and independent probe

Read [full 12-project comparison](RESEARCH_12_SONOFF_APPROACHES_20261119.md).

With the APK running on a real Android device and phone IP from `NET.ACTIVE`, on a PC connected to the same trusted LAN run:

```powershell
cd C:\sonoff
py tests\probe_private_server.py --host 192.168.0.192
```

Replace the example IP with the current phone IP. It checks `1081` (`/health`) and `8090` (`/api/ws` handshake) independently; no secrets, relay requests or device registrations. This check accepts the phone's **self-signed diagnostic TLS certificate** and therefore does NOT demonstrate that a stock Sonoff will accept it.

Old firmware may connect WebSocket on `/` instead of `/api/ws`, may omit `apikey` after register, or use a distinct `apikey` after a valid dispatch; v1.7 supports these only after authenticated session proof. No open unauthenticated device enrollment is allowed.
