# Local-first onboarding: what is proven and what is not

Target: SONOFF S60TPF (original Wi-Fi model, UIID 190). This build does NOT use eWeLink cloud/OAuth or register devices with eWeLink. It does NOT change a device's firmware or Wi-Fi settings.

## Verified behavior from official/maintained references

- The stock S60TPF eWeLink LAN protocol is encrypted using a per-device `devicekey` (SonoffLAN).
- Discovery via mDNS works without that key, but encrypted control does not.
- The normal S60TPF vendor onboarding guide uses eWeLink. The vendor does not document a fully account-free local commissioning API for the stock S60TPF.
- The *different* S60TPF-M model supports Matter and a local web console, with a separate commissioning mechanism.
- Older Sonoff-compatible APs can sometimes answer `GET http://10.10.7.1/device` with `deviceid` and `apikey`. Whether the stock S60TPF implements this is **not known**.

## What was implemented in this build

1. Removed the visible manual device-key form. The app **never guesses the key** or claims to be connected without authentication.
2. Added a separate cloud-free setup page. Upon explicit user action, it attempts the **read-only** AP request `GET /device` using the phone's Wi-Fi network. It will not send the request over cellular.
3. If a valid 10-character `deviceid` and `apikey` are returned, it saves the key in Android Keystore without displaying or exporting it. This *may* allow automatic LAN control **after the plug is connected to the home Wi-Fi**.
4. The app does **not** enter pairing mode, perform a factory reset, flash firmware, send SSID/password, or contact a cloud server.
5. If the device does not expose this API, it clearly reports that the method is unsupported. It does not pretend discovery equals successful pairing.

## Required safe physical test

- Use a device owned by the tester; never open a mains-connected plug enclosure.
- Verify the model label. Do **not** assume that S60TPF supports older `ITEAD-...` pairing APs.
- If the device *already* exposes a compatible setup AP, connect the phone to that AP via Android Wi-Fi settings.
- Open the app -> New plug -> Check local connection.
- If the AP returns the expected details, the key is saved locally. The Wi-Fi provisioning into the home network has **not** been implemented and must be designed based on confirmed S60TPF firmware behavior.
- If the AP cannot be reached, do not reset a live device that the owner depends on simply to make the app work. Export the diagnostic log and stop.

## Major unmet requirements

- No confirmed zero-cloud, zero-vendor-app factory provisioning for **stock S60TPF**.
- No confirmed device-initiated connection to Android's HTTP panel (`127.0.0.1` is loopback only). Standard stock LAN reverses that direction: Android initiates to plug:8081.
- No physical validation of this new provisioning path.
- No setup without providing *any* credentials for a brand-new Wi-Fi plug: a device must receive home-network credentials, or an equivalent commissioning mechanism, through an authorized method.

## Engineering alternatives

1. Choose S60TPF-M / another open local API (Matter, MQTT, WebSocket). Commissioning still requires a pairing QR/code or local access credentials; it does not require an eWeLink account.
2. If the owner explicitly permits replacing firmware on S60TPF, ESPHome/Tasmota can be independently controlled locally, but this changes the factory firmware, requires physical-device testing and may involve electrical/safety/warranty risks.
3. Preserve factory S60TPF firmware with eWeLink encrypted LAN: requires `devicekey` obtained through an authorized initial workflow. This is not the cloud-free first-use experience requested.

Sources:
- https://support.sonoff.tech/s60tpf-usermanual/
- https://support.sonoff.tech/s60tpf-m-usermanual/
- https://github.com/AlexxIT/SonoffLAN
- https://github.com/jcarcaboso/sonoff-local
- https://sonoff.tech/blogs/news/no-tasmota-and-ewelink-cloud-to-control-the-sonoff-device-yes
