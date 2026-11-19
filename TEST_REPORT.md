# v1.7.0 verification summary — 2026-11-19

- Project source: modified from the previously prepared v1.6.0 Android ZIP. **No UI redesign**, AES LAN, DIY, SoftAP and passive Matter discovery retained.
- **34 / 34 host-side regression tests PASSED** with `python -m unittest discover -s tests -q` (after adding the legacy compatibility / private network probe checks).
- Java source passes targeted `javac` test-stub compilation for legacy TLS server, AP provisioner, protocol router, device/session model, controller and Android foreground service. **Not equivalent to full Android SDK Gradle build**.
- Private dispatch admission: strict 90-second TTL, source-IP and device-ID matching, one-use only, no unauthenticated registration, no replay — Java executable self-test passed.
- Independent listener configuration: HTTPS `1081`, WSS `8090`; private server /health only on private phone Wi-Fi; `/` and `/api/ws` WS path compatibility.
- Probe script validates private destination only. App-generated Android TLS certificate acceptance by a real factory device remains unverified.
- **Missing physical evidence:** Gradle `assembleDebug`, Android Keystore handshake and real Wi-Fi listener, successful device `POST /dispatch/device`, WSS registration, meter readings, relay confirmed on customer's plug. These are essential before production release.
- See `docs/RESEARCH_12_SONOFF_APPROACHES_20261119.md` for 12 checked repositories, evidence and support boundaries.

---

# v1.6.0 Laboratory results (2026-11-19)

- 30 / 30 Python/Java integration and compile-stub tests passed.
- New tests validate Java syntax for `LegacyPrivateServer`, `LegacyApProvision`, `LegacyCloudProtocol`, and `SonoffForegroundService`, and constant-time comparison of credentials with an executable Java self-test.
- `node --check` validates embedded UI JavaScript syntax; HTML panel includes explicit consent before legacy `/ap` reprovisioning and multi-relay controls.
- **Not performed:** actual Android Gradle APK build (Android SDK not present); real Android Keystore certificate TLS handshake; real phone listener on Wi-Fi; legacy firmware certificate acceptance; physical Sonoff 3.6.4; relay ON/OFF with verified state.
- Therefore **NOT production-verified / not 100% hardware-compatible**. See `docs/PRIVATE_SONOFF_SERVER.md`.

---

# Sonoff Local v1.5.0 — лабораторний звіт (19.11.2026)

- `python -m unittest discover -s tests -v`: **26 PASS**.
- Перевірені LAN-симулятори: AES/getState/switches, DIY/info/switch/signal_strength, шифрований пристрій з read-only 401.
- Перевірений окремий HTTP-макет legacy AP: `/device` 200 з `deviceid`/`apikey`, 200 без ключа, 404 без endpoint.
- Чистий Java self-test `LegacyApIdentity`: whitelist SSID, виключення Matter AP, ID mismatch, маскування SSID.
- **Обмежена Java-компіляція:** `ApPairingManager`, `LocalPairingProbe`, `DeviceApNetwork`, `LegacyApIdentity` із Android API заглушками — PASS.
- Обмежена Java-компіляція: `Controller`, `LanCrypto`, `S60Device`, `SonoffProfiles`, `ProtocolRouter`, `LogBook` із окремими Android/JSON заглушками — PASS.
- Інтерфейс: ті самі вкладки й кнопки, лише статус `ACCESS_KEY_REQUIRED` тепер відображається як «Доступ відхилено». Жодного додаткового блоку UI.
- Підключення AP відбувається через Android `WifiNetworkSpecifier` (user-approved), не автоматичне приховане перемикання мережі.

## Не перевірено

- Повна Android Gradle/SDK збірка `assembleDebug` у цьому середовищі (Android SDK відсутній).
- Фактичний системний діалог Wi-Fi/мережевий callback на телефоні.
- Фізична Sonoff S31 і її AP endpoint.
- Фізична S60TPF і наявність `/device` в заводській прошивці.
- Жодного твердження про 100% успішне підключення всіх Sonoff без devicekey.

Деталі: `docs/LEGACY_AP_FALLBACK.md`.
