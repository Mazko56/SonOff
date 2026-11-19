# Sonoff Local Multi-Protocol v1.4.0 — тестування 19.11.2026

- Python `unittest discover -s tests -p 'test_*.py'`: **20 PASS**.
- У складі: перевірки AES і HTTP протокольного симулятора, відкритого DIY, read-only 401, mDNS, правильних маршрутів, збереженого HTML.
- `SonoffProfilesSelfTest`: моделі 190/32/181/126, вибір switch/switches, масштабування old/new firmware, LED fallback — PASS.
- Java синтаксична перевірка `Controller`, `S60Device`, `ProtocolRouter`, `SonoffProfiles`, `LanCrypto`, `LogBook` із обмеженими Android та JSONObject заглушками — PASS.
- HTML SHA-256 = `44bc8c4f5d48bf0929e118c6bf86fd440872250e5439bc603dc653518fa89b35`; попередній інтерфейс не змінено.
- Перевірено на наданих вихідних кодах AlexxIT/SonoffLAN і Urban82/sonofflan.

**Не перевірено:** повний Android SDK/Gradle build та установлення APK; реальна S60TPF або інша фізична Sonoff; фізичне ON/OFF; ключове обмеження `error=401` без `devicekey`; робоче Matter commissioning. Ключ із зашифрованої заводської Sonoff ці механізми автоматично не отримують. `sledOnline` fallback — ідемпотентний запис (не гарантовано без зношування EEPROM/flash), обмежено до одного разу на хвилину.
