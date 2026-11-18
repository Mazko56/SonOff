# Дослідження Sonoff S60 і Android LAN (18.11.2026)

## Основні висновки

- S60TPF — це штатна Wi-Fi модель із можливістю керування, моніторингу споживання та індикації через eWeLink. S60TPF-M (Matter) та S60ZBTPF (Zigbee) відрізняються протоколами. Прямий контроль цього APK обмежено S60TPF.
- SonoffLAN зберігає оригінальну прошивку й дозволяє LAN/Cloud одночасно. За версією SonoffLAN UIID 190 має `switches`, `current`, `power`, `voltage`, `dayKwh`, `monthKwh`, `configure`, `sledOnline`, `rssi`.
- Шифрований LAN-протокол: Android відкриває з'єднання до HTTP-сервера розетки, `http://IP:8081/zeroconf/<command>`; mDNS `_ewelink._tcp.local`; AES-128-CBC на основі MD5(devicekey). Ключ потрібно отримати авторизовано.
- Специфічна проблема деяких S60TPF: анонси mDNS містять SRV+TXT, але не A-record. Це потребує резервної обробки адреси; навіть workaround має обмеження при multicast-relay. Система не повинна вводити IP вручну як основний маршрут.
- `getState` на деяких прошивках лише підтверджує доступність і не надає дані; показники необхідно приймати із повідомлень про стан. Деколи використовується ідемпотентний запит `sledonline` з поточним станом індикатора для примусового оновлення.
- Android 17 додає локальні мережеві дозволи для застосунків із targetSdk >=37. Ця збірка targetSdk 35.
- Жоден із перевірених офіційних матеріалів не підтверджує, що штатна S60TPF сама може ініціювати підключення до стороннього локального сервера телефона без зміни конфігурації/прошивки; поточна архітектура реалізує пряме локальне керування з телефона, але НЕ цю буквально сформульовану вимогу.

## Основні перевірені публічні джерела

1. SONOFF official manual S60TPF — https://support.sonoff.tech/s60tpf-usermanual/
2. SONOFF official manual S60TPF-M — https://support.sonoff.tech/s60tpf-m-usermanual/
3. SONOFF official manual S60ZBTPF — https://support.sonoff.tech/s60zbtpf-usermanual/
4. SONOFF EU Data Act equipment list — https://sonoff.tech/en-eu/pages/eu-data-act
5. SONOFF iPlug Wi-Fi S60 catalog — https://sonoff.tech/
6. SonoffLAN README — https://github.com/AlexxIT/SonoffLAN
7. SonoffLAN SUPPORTED DEVICES — https://github.com/AlexxIT/SonoffLAN/blob/master/DEVICES.md
8. SonoffLAN local transport implementation — https://github.com/AlexxIT/SonoffLAN/blob/master/custom_components/sonoff/core/ewelink/local.py
9. SonoffLAN devices/UIID 190 mapping — https://github.com/AlexxIT/SonoffLAN/blob/master/custom_components/sonoff/core/devices.py
10. SonoffLAN S60TPF missing A-record — https://github.com/AlexxIT/SonoffLAN/issues/1871
11. SonoffLAN S60TPF LAN problem — https://github.com/AlexxIT/SonoffLAN/issues/1772
12. SonoffLAN firmware notes / releases — https://github.com/AlexxIT/SonoffLAN/releases
13. SonoffLAN local switch behaviors and telemetry issue — https://github.com/AlexxIT/SonoffLAN/issues/975
14. SonoffLAN energy telemetry issue — https://github.com/AlexxIT/SonoffLAN/issues/1114
15. SonoffLAN getState issues — https://github.com/AlexxIT/SonoffLAN/issues/1442
16. SonoffLAN getState issues — https://github.com/AlexxIT/SonoffLAN/issues/1907
17. eWeLink official API docs — https://github.com/CoolKit-Technologies/eWeLink-API/blob/main/en/APICenterV2.md
18. eWeLink community LAN client — https://github.com/nocmt/ewelink-api-next/blob/main/docs/en/LAN-Control.md
19. Homebridge eWeLink LAN implementation notes — https://github.com/homebridge-plugins/homebridge-ewelink/blob/latest/CLAUDE.md
20. Sonoff-local (independent cross-check) — https://github.com/jcarcaboso/sonoff-local
21. Android official local network permission — https://developer.android.com/privacy-and-security/local-network-permission
22. Android official NsdManager API — https://developer.android.com/reference/android/net/nsd/NsdManager
23. Android official Android 17 behavior — https://developer.android.com/about/versions/17/behavior-changes-17
24. GitHub official setup-android Action — https://github.com/android-actions/setup-android
25. SONOFF official product information — https://sonoff.tech/products/sonoff-iplug-zigbee-smart-plug-s60-series

*Перелік містить ключові першоджерела та перехресні підтвердження, а не вигадану «100-відсоткову перевірку за 100 джерелами». Заявляти 100 перевірених джерел було б некоректно: достовірність залежить від виробника, реалізацій протоколу та фізичного тесту, а не від кількості сторінок.*

## Архітектура

```
SONOFF S60TPF (eWeLink firmware)    Wi-Fi router       Android
   mDNS multicast: TXT/SRV ───────────────┬────────→ mDNS/NSD listener
   HTTP LAN server: :8081 ◀───────────────┼────────── encrypted LAN HTTP commands
   eWeLink cloud connection ──────────────┼────────── unaffected by APK
                                          └─────────── Android loopback HTTP: :18765
                                                       WebView UI + diagnostics
```

### Чого НЕ можна гарантувати

- Стопроцентного виявлення в мережах з ізоляцією клієнтів або multicast-фільтрами.
- Необмеженого керування «без введення будь-чого»: індивідуальний ключ — частина захисту пристрою.
- Повного енергетичного архіву, якщо прошивка не видає ці дані через LAN.
- Підключення розетки «як клієнта» до вебсервера телефона без зовнішньої зміни її налаштувань: це інша модель комунікації, не підтверджена штатним протоколом.
- Безперебійної роботи у фоні за Android battery restrictions: наразі activity-scoped scanning.

## Legacy Sonoff-Server.zip

Архів `Sonoff-Server-master.zip` від користувача — приклад старого cloud endpoint replacement, орієнтованого на Sonoff Basic. Його підхід із перехопленням хмарного сервера не використовується: сучасний certificate pinning і вимога зберегти штатну eWeLink роботу роблять його непридатним для S60TPF.
