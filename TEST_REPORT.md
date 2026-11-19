# Звіт про перевірку v1.2.0

- 11 Python-тестів `python -m unittest discover -s tests -p 'test_*.py'` — виконано успішно.
- Java-класи `Controller`, `LanDiagnostics`, `S60Device`, `LanDiscovery`, `MdnsPacket`, `LogBook` — успішно скомпільовано через `javac` з тестовими Android/org.json заглушками.
- HTML `index.html` — SHA-256 `44bc8c4f5d48bf0929e118c6bf86fd440872250e5439bc603dc653518fa89b35`, байтово ідентичний попередньому архіву AutoLocal.
- `simulator --public-readonly` — лабораторна відкрита телеметрія; relay writes без шифру відхиляються; за стандартного режиму read-only спроби отримують 401.
- **Не виконано:** повна Gradle/Android SDK збірка та реальні тестування на фізичних Sonoff. Для оцінки фізичної сумісності потрібні APK із GitHub Actions і журнал від замовника.
