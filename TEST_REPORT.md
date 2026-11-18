# Verification report — RC2, 2026-11-18

- **Java syntax against lightweight Android API stubs:** PASS (`javac -encoding UTF-8`), but **not a real Android Gradle build**.
- **Android mDNS TXT/SRV-without-A packet self-test:** PASS (`MdnsPacketSelfTest`).
- **Protocol test suite:** PASS, 3 unit/integration tests covering MD5/AES-128-CBC, malformed or missing A record, encrypted ON/OFF, getState, LED switch, configure read and startup change. This runs against `tests/fake_s60.py`, **not a real S60**.
- **HTML embedded JavaScript parse check:** PASS (`node --check`).
- **AndroidManifest.xml and XML launcher:** PASS (`xml.etree.ElementTree` parse).
- **GitHub Actions YAML parse:** PASS (`PyYAML` parse).
- **Real Android APK build:** NOT RUN in current environment (no Android SDK/Gradle).
- **Actual S60TPF firmware, eWeLink coexistence, real power data, and device→phone server initiation:** NOT TESTED / NOT CONFIRMED.
- **OAuth devicekey acquisition without initial manual entry:** NOT IMPLEMENTED (requires authorized eWeLink Developer API credentials and app-specific authorization design).

See docs/ACCEPTANCE.md for the hardware checklist. A successful simulator test cannot be represented as verified hardware compatibility.
