# Source verification and implementation decisions (2026-11-18)

## Primary / official sources

1. SONOFF S60TPF official instruction: https://support.sonoff.tech/s60tpf-usermanual/ — Wi-Fi 2.4GHz ESP32-C3, S60TPF Type F, 16A, 4000W; electricity monitoring and pairing procedure. Do NOT confuse with the new Matter model.
2. SONOFF iPlug product page: https://sonoff.tech/en-nl/products/sonoff-iplug-wi-fi-smart-plug-s60-series — eWeLink LAN control for S60 family, energy monitoring capabilities.
3. SONOFF's own iHost synchronization article: https://sonoff.tech/en-de/blogs/news/how-to-connect-devices-to-ihost-for-control-and-management — discovers devices over mDNS, retrieves authorized encryption key via cloud, then controls them on LAN.
4. S60TPF-M *different model* manual: https://support.sonoff.tech/s60tpf-m-usermanual/ — Matter protocol, local web console etc. Features of this variant must **not** be incorrectly attributed to plain S60TPF.

## Implementation corroboration (community; not manufacturer guarantees)

5. AlexxIT SonoffLAN: https://github.com/AlexxIT/SonoffLAN ; https://github.com/AlexxIT/SonoffLAN/blob/master/DEVICES.md — lists S60TPF UIID 190, local type `plug`, response to LAN requests, original firmware, cloud/local concurrency.
6. SonoffLAN local protocol source: https://github.com/AlexxIT/SonoffLAN/blob/master/custom_components/sonoff/core/ewelink/local.py — `_ewelink._tcp.local.`, TXT `id/type/encrypt/iv/data1..4`, `devicekey`, AES-CBC, `POST http://{ip}:8081/zeroconf/{command}`. `getState` used for generic status queries.
7. SonoffLAN issue #1871 filed Sep 6 2026: https://github.com/AlexxIT/SonoffLAN/issues/1871 — S60TPF sometimes sends SRV+TXT but **no A record**; fallback from packet source may be necessary. Not all units are affected.
8. Sonoff Local community project: https://github.com/jcarcaboso/sonoff-local — UIID 190 `switches` with `operSide:1`, `getState`, power/current/voltage/dayKwh/monthKwh scaled 0.01, mentions diagnostic testing and that this community project **has not been tested against real physical S60TPF** as of its README. Do not present the project as hardware proof.
9. eWeLink local API library docs: https://github.com/nocmt/ewelink-api-next/blob/main/docs/en/LAN-Control.md — request structure, encryption and LAN endpoints.
10. SONOFF DIY protocol (older devices): https://github.com/itead/Sonoff_Devices_DIY_Tools/blob/master/other/SONOFF%20DIY%20MODE%20Protocol%20Doc%20v1.4.md — applies to documented DIY-compatible models, **not proven for S60TPF**. Re-pairing or switching modes may disrupt eWeLink and is not used here.

## Explicitly rejected architecture

11. `vponomarev/Sonoff-Server` (user-supplied old ZIP): https://github.com/vponomarev/Sonoff-Server — README announces closure after SSL certificate pinning. Requires intercepting device-cloud communication and has historical support for Sonoff Basic only. It is **not** an S60TPF solution.

## Confirmed / unconfirmed split

**Supported by sources:** S60TPF factory Wi-Fi LAN protocol can be controlled by third party implementations; the usual client sends encrypted requests to the device itself on port 8081; NSD/mDNS discovery, AES key, relay command and sensor scales are well documented by community projects. Original firmware can co-exist with eWeLink in local integrations.

**Not confirmed by test here:** whether our Java implementation interoperates with the exact S60TPF/firmware; whether device accepts all commands; whether original eWeLink remote monitoring remains completely unchanged with concurrent sessions; whether the plug exposes all historical telemetry through LAN; and whether stock firmware can initiate a connection to a phone-hosted custom HTTP server.

## Why the local server runs on loopback only

The user asked for an Android app that raises an HTTP server AND the *plug* connects to it. Running a server on the phone can serve a local control panel and API, but does not mean the plug becomes an HTTP client. The actually observed protocol is **phone -> plug:8081**. Binding the phone server on `0.0.0.0` without authentication would add LAN remote-control exposure and does not fix this incompatibility. Any requirement for phone as server of device-initiated connections needs manufacturer support for callback, MQTT, custom WebSocket or firmware changes; none has been demonstrated for factory S60TPF.

## Useful next source material for a real device test

Exact model printed on plug, firmware version visible in eWeLink, Android version, router network mode, whether `devicekey` obtainable through the user's eWeLink account, and exported redacted diagnostics from first run. Never ask the user to send passwords or device keys in chat.
