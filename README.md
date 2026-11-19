## Оновлення 1.7.1: виправлення збірки GitHub Actions

Виправлено збій збірки APK через застарілий тест `claimLocal()`: кнопка
видалена у поточному інтерфейсі, отже тест повинен перевіряти її **відсутність**.
Додано `tests/test_ui_javascript.py`: перевірка синтаксису фактичного JS у WebView
через Node.js і відповідності HTML-обробників функціям.
У GitHub Actions Node.js встановлюється перед тестами.

Якщо вихідні ZIP накладалися один на одного через `Expand-Archive -Force`,
застарілі тести могли залишитися в `C:\sonoff\tests`. Запустіть
`scripts/sync_tests_windows.ps1` із параметром `-ArchivePath`: скрипт
архівує лише тести `test_*.py`, відсутні у ZIP, у тимчасовій папці, а не видаляє їх без копії.

Перевірка пакета: 37/37 локальних тестів; повну Android Gradle-збірку
підтвердить лише GitHub Actions / Android SDK. Протокол фізичної
розетки це виправлення не змінює.

---

# Sonoff Local v1.7.0 — multi-route private server (HARDWARE NOT QUALIFIED)

**Warning: private-server support and broad LAN discovery do NOT prove 100% control of the customer's factory firmware. TLS pinning on some models can prevent legacy private server connections.**

Prior v1.6: private LAN-bound HTTPS/WSS server (not the loopback WebView server), registered-device key verification, old AP `/ap` provisioning only with explicit consent, device-initiated `register/update/query`, multiple relay outlets, foreground-service runtime and corrected asynchronous Wi-Fi scan reporting. Original AES LAN, DIY, AlexxIT and Urban82 remain. See [docs/PRIVATE_SONOFF_SERVER.md](docs/PRIVATE_SONOFF_SERVER.md) for v1.6 design.

**New v1.7:** two separate Wi-Fi-bound TLS ports (`1081` HTTPS dispatch + `8090` WSS), legacy WebSocket path `/` and `/api/ws`, 90-second single-use authenticated dispatch proof for old firmware session compatibility, WebSocket sequence echo and read-only desktop probe. **34/34** host-side tests passed. Full Android APK build and on-device verification are pending. See [12-project technical analysis](docs/RESEARCH_12_SONOFF_APPROACHES_20261119.md).

# Sonoff S60 — Auto Local (experimental Android build)

## Що реально реалізовано

- Автопошук через mDNS/NSD, резерв для пакетів без A-записів.
- Локальне шифроване керування **лише після отримання законного devicekey**.
- Збереження ключів Android Keystore (дані не передаються розробнику).
- Автоматичне **обмежене зчитування** `/device` із точки доступу пристрою лише коли телефон реально одержав IPv4 `10.10.7.x/24`; на звичайному Wi-Fi запитів до `10.10.7.1` немає.
- Короткий інтерфейс без тестових та технічних пояснювальних блоків; журнал подій і експорт доступні в «Допомога».
- Вимірювання і керування в інтерфейсі лише з реально отриманих та підтверджених даних.

## Що не реалізовано / не підтверджено

- Для **заводської S60TPF (не S60TPF-M)** офіційно не підтверджений `/device` AP endpoint. Отже автоматична первинна авторизація без eWeLink **не гарантується**.
- Передавання домашніх Wi-Fi-параметрів через legacy `/ap` є лише за **явної згоди користувача** та тільки для підтверджено сумісного старого AP. Безумовне первинне додавання сучасної S60TPF без eWeLink **не підтверджено**.
- У v1.6 додано окремий HTTPS/WSS-сервер для старих сумісних Sonoff, але фізичний пристрій на заводській 3.6.4 не перевірено; modern certificate pinning може блокувати його.
- Не перевірено роботу із фізичною S60TPF; на симуляторі можна тестувати тільки протокол, не можливості заводської прошивки.
- Для Matter-моделі S60TPF-M потрібен окремий стек Matter та процедура commissioning, їх цей додаток не реалізує.

## Збірка

Вміст ZIP покласти безпосередньо в корінь GitHub-репозиторію. Використати `.github/workflows/build-apk.yml` або Android Studio (SDK 35, JDK 17). Успішна компіляція не підтверджує реальної сумісності.

## Документація

- https://support.sonoff.tech/s60tpf-usermanual/
- https://support.sonoff.tech/s60tpf-m-usermanual/
- https://github.com/AlexxIT/SonoffLAN
- https://github.com/AlexxIT/SonoffLAN/blob/master/DEVICES.md
- https://sonoff.tech/blogs/news/no-tasmota-and-ewelink-cloud-to-control-the-sonoff-device-yes
- https://github.com/CoolKit-Technologies/eWeLink-API/blob/main/en/OAuth2.0.md

Старий протокол DIY Mode 2.0 був документований для BASICR3/RFR3/MINI/D1, не S60TPF. Пристрій не можна авторизувати простим приховуванням поля devicekey.

## Оновлення: локальна діагностика без ключа (v1.2.0)

Без змін HTML-інтерфейсу: збирання детального журналу мDNS/NSD, моделі/імені (якщо оголошені), TCP, read-only HTTP, відмов авторизації та мережевих маршрутів. Додано Wi-Fi-bound запити, read-only гілку навіть без ключа, обережні команди для `enhanced_plug` після **підтвердженого** доступу. Опис і лабораторні команди: [ДІАГНОСТИКА_UA.md](ДІАГНОСТИКА_UA.md).

**Важливо:** stock S60TPF потребує `devicekey` для штатного зашифрованого моніторингу/керування. Без eWeLink, сервера і раніше збереженого ключа додаток може лише виявляти пристрій та пробувати read-only можливості без гарантії отримання показників. Жоден діагностичний HTTP-відгук не розблоковує реле.

## Оновлення: багатопрофільне локальне підключення (v1.3.0)

- **eWeLink LAN AES** (`type=plug/enhanced_plug/th_plug/strip`, `encrypt=true`): автовиявлення, TCP, протокольна діагностика, локальний `getState -> info` fallback і розшифрування mDNS/HTTP, **лише якщо ключ уже є в Android Keystore**. Без ключа `error=401` не обходиться.
- **Офіційний DIY API v1** (`type=diy_plug`, немає encrypt=true): автовизначення, правильне `/zeroconf/info`, читання `/zeroconf/signal_strength`, реле `/zeroconf/switch` **лише після успішного локального читання стану**. Не підтримує заводську S60TPF, якщо вона не має DIY-режиму.
- **Інший незашифрований LAN:** перевірка читання без ключа, керування лише для підтверджених `plug` / `enhanced_plug` з відкритою успішною відповіддю і реальним станом реле.
- **Matter:** пасивний пошук `_matter._tcp.` та `_matterc._udp.` і діагностика в журналі. Matter-commissioning і керування **не реалізовані** та не замінюються eWeLink LAN-протоколом.
- Назва/модель/UIID/прошивка з mDNS, якщо вони реально оголошені. Тип `plug`/`enhanced_plug` — **не точна ідентифікація**, тому журнал подає лише кандидатів; незаявлені поля не вигадуються.
- **Без зміни HTML/UX** (контрольний SHA див. TEST_REPORT). Немає eWeLink OAuth, серверів, спроб грубого підбору ключів, скидання та перепрошивання.
- Лабораторна перевірка режиму DIY: `py tests\fake_s60.py --host 0.0.0.0 --port 8081 --advertise --advertise-ip <LAN_IPV4_PC> --diy`. Це **лише симуляція протоколу**, не доказ, що заводська S60TPF працює без ключа.

Підтверджуючі першоджерела:
- https://github.com/itead/Sonoff_Devices_DIY_Tools/blob/master/other/SONOFF%20DIY%20MODE%20Protocol%20Doc%20v1.4.md
- https://github.com/AlexxIT/SonoffLAN/blob/master/DEVICES.md
- https://github.com/AlexxIT/SonoffLAN/blob/master/custom_components/sonoff/core/ewelink/local.py
- https://support.sonoff.tech/s60tpf-usermanual/
- https://support.sonoff.tech/s60tpf-m-usermanual/
- https://support.sonoff.tech/sonoff-openedge-console/

## Оновлення 1.4.0 — адаптація протоколів AlexxIT + Urban82

Працює **зі штатною прошивкою**; жодних запитів до хмари eWeLink або перепрошивання.
Перенесено правила протоколу з наданих користувачем джерел (не Python-проєкти Home Assistant):

- Еталон SonoffLAN: `UIID 190` (S60TPF, POWR320D, POWCT), `/zeroconf/switches` + `operSide=1`, `/zeroconf/sledOnline` (важливий правильний регістр `O`), індивідуальні поля `dayKwh`, `monthKwh`, датчики із масштабом ×0.01.
- Еталон Urban82: вибір `switch` проти `switches` за **реально отриманою та розшифрованою структурою стану**; `enhanced_plug` із одиночним `switch` передає вимірювання без ділення на 100, тоді як `switches[]` із вимірювачем використовує ×0.01.
- Для `th_plug`/`UIID 181` враховано `mainSwitch` і `deviceType`, для `UIID 126` передбачено локальний запит `statistics` за умови **наявного ключа і вже підтвердженого стану**.
- Запит стану `getState` → `info`; коли вони не дають нового стану, тільки **після автентифікації** можливий обмежений (≤1/хв) повтор поточного значення LED через `sledOnline`. Це ідемпотентна команда запису і застосовується лише до сумісного профілю.
- Реле не перемикається для невідомого каналу або якщо `outlet=0` не було в підтвердженому стані; відповіді HTTP 200/`error=401` не вважаються авторизацією.
- Уся інформація про маршрут вибору профілю й відповіді надходить у попередній журнал. **HTML-інтерфейс не змінений**.

**Чесне обмеження:** код AlexxIT та Urban82 **не містить механізму обходу `devicekey`** для зашифрованої заводської прошивки. Отже фізична розетка без дозволеного ключа може і надалі відмовляти з `401` та не надавати телеметрії/керування. «100% на кожній Sonoff» не доведено. Зібрати APK необхідно через GitHub Actions і перевірити на реальній розетці.

Деталі портованих правил — [docs/ADAPTERS_20261119.md](docs/ADAPTERS_20261119.md).

## Оновлення 1.5.0 — незалежний Legacy SoftAP fallback (S31-style)

- Новий `ApPairingManager.java`: під час запуску/штатної кнопки «Шукати» перевіряє **реальні Wi-Fi скан-результати**, знаходить лише `ITEAD-*`, підключається через Android `WifiNetworkSpecifier` (системний діалог), перевіряє DHCP-підмережу `10.10.7.x`, читає **GET `/device`** через конкретний `Network`, без перенаправлення трафіку основного застосунку.
- `deviceid` і `apikey` валідуються, повний ID звіряється з SSID, ключ зберігається в **Android Keystore** без показу в інтерфейсі чи логах. AP-зв'язок звільняється, запускається повторний LAN-пошук. Відсутність AP завершується конкретним `AP.SCAN.NONE`, а не нескінченним очікуванням.
- Додано read-only **GET `/` HTTP-код як резервну діагностику** для AP, в якого `/device` відсутній. Конфігурація AP/SSID, прошивка й реле не змінюються.
- Для factory-encrypted LAN, що відповідає `error=401`, картка завершує спробу зі статусом **«Доступ відхилено»** замість нескінченного «Очікує доступу». Нових вікон/карток в HTML немає. Лог містить точну причину.
- Залишаються всі механізми v1.4.0: encrypted AES, DIY, mDNS/NSD, IP fallback, AlexxIT/Urban82, пасивний Matter.
- Дозволи Android `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`. ОС може потребувати ввімкненої геолокації для сканування та системного підтвердження локального Wi-Fi.
- **Немає eWeLink OAuth/cloud, перебору ключів, автоматичного скидання чи перепрошивання.** Штатна Sonoff S60TPF не має підтвердженої підтримки legacy `/device` на AP; при невідповідності прошивки ключ не буде отримано.

Повний опис, результати, журнал і застереження: [docs/LEGACY_AP_FALLBACK.md](docs/LEGACY_AP_FALLBACK.md).
