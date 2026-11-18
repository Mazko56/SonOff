# Real S60TPF acceptance — do not confuse simulated with physical testing

1. Photograph/check label of device (must be **S60TPF** standard Wi-Fi, not `S60TPF-M` Matter or `S60ZBTPF` Zigbee).
2. Record firmware version via official eWeLink app; preserve its configuration and pairing.
3. Phone on same LAN, Wi-Fi multicast working. Observe `DEVICE.FOUND` and actual measured IP without manual input; verify against router if source fallback.
4. Obtain key through authorized existing eWeLink channel. Store only in app using encrypted preferences; do not share.
5. Observe a `MDNS.DECRYPT.OK` or `LAN.REPLY.STATE` event with non-simulated readings; compare voltage/current/power with eWeLink.
6. With safe lamp plugged in: turn ON via app; verify physical power and status in BOTH app and eWeLink. Turn OFF and repeat.
7. Toggle device physically and from eWeLink; app eventually updates after mDNS or polling. Compare source timestamps.
8. Verify eWeLink cloud/offline modes, no re-pair, no firmware change, no disrupted status (10+ operations).
9. Export redacted logs and measure command response rate and delays; classify failures (discovery/key/HTTP/response/state).
10. **Mandatory independent check:** does device itself initiate a connection to phone server? Expected on stock eWeLink firmware: **NO**, because phone is client to device HTTP server. This request should be renegotiated explicitly rather than marked "passed".

Passing the Python mock tests or in-app simulator cannot close items 3-10.
