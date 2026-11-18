# Sonoff S60 Lab — Android LAN prototype (source)

**Status as of 2026-11-18: laboratory prototype; never verified on a physical Sonoff S60TPF.**
The provided files are an Android Studio Gradle source project, **NOT an installable APK**. An Android toolchain is not available in the authoring environment. Build on Android Studio or GitHub Actions. The embedded simulator and Python unit tests have been exercised; actual plug interoperability has NOT been tested.

## Target / supported functions

- **Sonoff S60TPF Wi-Fi** (ESP32-C3; eWeLink LAN; UIID 190). Not S60ZBTPF Zigbee or S60TPF-M Matter.
- Automatic discovery using Android NSD + raw UDP mDNS `_ewelink._tcp.local` multicast `224.0.0.251:5353`.
- Special **missing A-record** fallback for S60TPF: infer sender IPv4 from packet *only when same packet contains a matching service TXT*. Do not use this shortcut through multicast reflectors without verifying the address.
- Local encrypted LAN `HTTP POST /zeroconf/switches`, `getState` on port 8081, AES-128-CBC PKCS7 with MD5(devicekey) key derivation. This imitates observed SonoffLAN local protocol, not Sonoff's documented public API.
- UI: relay on/off, voltage, amperes, power, day/month energy, RSSI when present, response and error status.
- The mDNS type alone is **not a reliable retail model identifier**. Before relay control, the operator must confirm that the physical label is S60TPF; this avoids controlling other eWeLink switches accidentally.
- A device key can be stored **once** in Android Keystore-encrypted preferences. No IP is entered, but **secure pairing/key acquisition cannot be bypassed** by the app.
- Local loopback web server `http://127.0.0.1:18765/` inside the Android application, dashboard web UI, GET `/api/state`, `/api/logs`, authenticated locally by same-origin and custom mutation header. Server not exposed to LAN; see architecture below.
- Share diagnostic events using Android ACTION_SEND. Keys, tokens, Wi-Fi SSIDs, raw encrypted payloads are **not** logged. Device IDs, IPs and errors **are** logged; user should review before sharing.
- Explicit in-app simulator, separate from any real device. Optional Python simulated Sonoff on desktop for transport tests.

## Architecture caveat (critical)

In eWeLink LAN, **the S60TPF itself listens on TCP 8081**. The Android phone discovers the plug and connects **as HTTP client** to the plug, while the phone's embedded HTTP server hosts the control panel. There is **no verified stock-firmware method** to make the S60TPF initiate an arbitrary persistent connection into an Android phone-hosted server. Insisting that the plug *must connect directly to the phone server* rather than accepting direct local HTTP requests **is not satisfied by this prototype** and may be impossible with the stock firmware. We do not change the device firmware, reset or hijack DNS/cloud traffic.

## Build installable debug APK

### Option A: Android Studio (Windows)

1. Install Android Studio with **Android SDK API 35**, **Build Tools 35**, and JDK 17.
2. Open this project folder in Android Studio, allow Gradle sync and dependency downloads.
3. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Install `app/build/outputs/apk/debug/app-debug.apk` on an Android device (Android 8.0+).
5. The Android app is debug-signed by Android Gradle Plugin. Do **not** use it as a production/security-audited release.

### Option B: GitHub Actions

1. Upload the folder **contents** to a **private** GitHub repository (do not commit any real device key).
2. Go to **Actions > Build Android debug APK > Run workflow** (or push to main).
3. Download the workflow artifact `SonoffS60Lab-debug-apk`, unzip; it contains `app-debug.apk`.

The workflow lives at `.github/workflows/build-apk.yml`. The pipeline has not run here because no authenticated repository/build runner was supplied. This is a *build recipe*, not proof of APK success.

## Testing on a phone

1. Install the APK, connect your phone and the real S60TPF to the **same LAN**; enable Wi-Fi and leave the app visible. Device is already provisioned using eWeLink (standard firmware).
2. The app automatically starts mDNS scan; press **Повторити пошук** as needed. Check network for multicast filtering, Wi-Fi AP isolation, VLAN, VPN, routers and mDNS proxy. It never asks you to manually input IP.
3. If device is detected and encrypted, supply its per-device `devicekey` **once**. The app will store it encrypted locally. **Do not send your device key or eWeLink account password to the developer.**
4. **Confirm the model as S60TPF** using the button after checking the printed label; mDNS does not reliably give a model. Then press **↻ Стан**, observe returned voltage/current/power/energy; test relay with **a safe low-power lamp** connected to the plug. Compare physical relay/LED, eWeLink app and application screen.
5. Turn OFF and ON once. Make sure eWeLink still reports correct state and can control the plug; compare telemetry while internet is available and offline. The app does not guarantee every metric is exposed in LAN replies.
6. Tap **Поділитися логами** to export logs if detection/decryption/replies fail. Review IP and device ID before sharing.
7. Use **Увімкнути симулятор** to test interface and on/off without a real device. This only demonstrates the app UI, not interoperability.

### How to obtain a devicekey safely

Devicekey is a per-device secret needed for encrypted eWeLink LAN; it is **not an IP, not your account password**, and cannot be derived from mDNS announcements. Existing SonoffLAN integrations fetch it via authorized eWeLink account API. The eWeLink developer API can also return it if you have your own Developer App ID/Secret and are authorized to access the device. We intentionally did not implement unofficial credential scraping or hardcode other apps' keys. See `docs/RESEARCH.md` and SonoffLAN docs. We **do not recommend resetting the plug to retrieve a key**, as the requirement is to preserve the official configuration.

## Python local fixture (optional, desktop)

```shell
python -m pip install cryptography
python -m unittest discover -s tests -p "test_*.py" -v
python tests/fake_s60.py --devicekey lab-test-key-only --port 8081
# optional multicast advertiser in a dedicated lab VLAN:
python tests/fake_s60.py --host 0.0.0.0 --advertise --missing-a --advertise-ip 192.168.1.25
```

Replace the example `192.168.1.25` with your PC's real LAN IP. For laboratory LAN testing, allow inbound TCP 8081 on the desktop firewall. The mDNS fixture advertises encrypted state updates. Enter **fake** key `lab-test-key-only` into the app when it discovers **fake** ID `1000abcdef`; this is not a real SONOFF secret.

The desktop fake S60 shows expected AES payload structure but cannot prove physical device compatibility. Android emulator network usually cannot directly receive host-machine multicast broadcasts, so use the **in-app** simulation to test the visual UI in an emulator. If testing network protocol in Android emulator, configure NAT/bridging/mDNS routing explicitly and ensure only a test device is targeted.

## Diagnostic table

| Symptom | Expected log code | Next check |
|--|--|--|
| Nothing found | `NSD`, `MDNS.BIND.ERROR`, `SCAN` | Same Wi-Fi/VLAN; multicast UDP 5353; LAN enabled; actual Wi-Fi S60TPF rather than Zigbee/Matter |
| Device found, no telemetry | `DEVICE.FOUND` then no `MDNS.DECRYPT.OK` | Has authorized devicekey been supplied? Does firmware send TXT data? |
| Device found, decryption fails | `MDNS.DECRYPT.FAIL` | Wrong/obsolete key, firmware format changed |
| Device found, wrong IP | `MDNS.NO_A` | Is an mDNS repeater/proxy involved? Confirm actual IP via router, diagnose raw packet |
| Relay not working | `LAN.REQUEST.FAIL`, `LAN.REPLY.ERROR` | TCP 8081 reachable; correct key; `switches` supported; right model |
| Relay physically moves but UI stale | `LAN.REPLY.OK` no state | S60TPF may not provide immediate status; watch mDNS, refresh; verify missing-A workaround |
| Simulator works, real device not | `SIM.RELAY` vs `LAN.*` | Simulator only validates software pathways, not hardware/firmware |

## Safety and limits

Do not use an unattended high-power load during initial tests. Do not bypass the 16 A / 4000 W rating or electrical protections. Avoid sharing key values. No cloud intercept, no OTA updates, no unverified controls (overcurrent thresholds etc.). No guarantee of automatic detection across isolated Wi-Fi/VLANs or all firmware versions. App remains active while its Activity runs and might pause in background due to Android process limits.

## Original legacy server ZIP

`Sonoff-Server-master.zip` (provided separately) replaces the *old cloud endpoint*. Its README explicitly says the project was closed because modern firmware uses **certificate pinning**. It supports old Sonoff Basic, not S60TPF. This project deliberately **does not** use DNS hijacking or the old alternate cloud server architecture, because it is incompatible with the stated requirement to preserve standard eWeLink monitoring.
"# SonOff" 
