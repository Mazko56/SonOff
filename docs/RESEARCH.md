# Дослідження Sonoff S60TPF / eWeLink LAN

Перевірено ключові **першоджерела** (документація виробника, власники реалізацій протоколу та issue-трекери). Це не доказ перегляду 100 окремих незалежних джерел; не можна створювати видимість цього числа.

## Базова модель

- SONOFF S60TPF: Wi-Fi; оригінальний eWeLink. Не плутати з S60ZBTPF (Zigbee) або S60TPF-M (Matter).
- SonoffLAN описує UIID 190 і `localtype=plug`, підтримку relays, LED, startup, power/current/voltage і day/month energy.
- Напруга/струм/потужність у протоколі масштабуються ×0.01; day/month kWh ×0.01. Залежить від фактично одержаних параметрів.
- SonoffLAN використовує `POST http://<device-ip>:8081/zeroconf/<command>`, deviceid, selfApikey, sequence та devicekey; параметри в AES-128-CBC (MD5-derived 128-bit AES key). Перевірено прикладом шифрування з симулятором.
- Для UIID 190 реле: `/zeroconf/switches` з `switches:[{outlet:0,switch:"on"|"off"}],operSide:1`, а не загальна команда `/zeroconf/switch`.
- `configure` command with `{configure:"get"}` читає налаштування. Змінювати їх треба тільки якщо розетка підтвердила `configure`.
- TXT/SRV можуть надходити без A-record. Джерело UDP може бути резервною адресою тільки після порівняння та невдалої спроби TCP з опублікованою адресою. Рефлектори mDNS можуть повертати IP посередника — це не універсальна гарантія.

## Ключ доступу

`devicekey` — секрет заводського протоколу, а не загальновідомий ключ і не пароль Wi-Fi. Без eWeLink-авторизації чи одноразового власницького надання ключа немає легітимного універсального способу «просто знайти пристрій і отримати криптографічний контроль». Офіційний API eWeLink повертає `devicekey` у списку пристроїв за належних прав застосунку/користувача. OAuth інтеграція потребує app credentials, згоди користувача, перевірки API та безпечного зберігання токенів. Вбудовувати секрет розробника без бекенду в публічний APK не можна.

## Додаткові можливості

Виробник може показувати в eWeLink графіки, розклад, таймери, inching, cloud automation. Наявність UI-кнопки eWeLink не доводить наявність локального API для цього параметра. Цей APK реалізує лише відомі LAN команди та приховує непідтверджені функції.

## Першоджерела і технічні кейси

1. https://support.sonoff.tech/s60tpf-usermanual/
2. https://support.sonoff.tech/s60tpf-m-usermanual/
3. https://github.com/AlexxIT/SonoffLAN
4. https://github.com/AlexxIT/SonoffLAN/blob/master/DEVICES.md
5. https://github.com/AlexxIT/SonoffLAN/blob/master/custom_components/sonoff/core/devices.py
6. https://github.com/AlexxIT/SonoffLAN/blob/master/custom_components/sonoff/core/ewelink/local.py
7. https://github.com/AlexxIT/SonoffLAN/blob/master/custom_components/sonoff/select.py
8. https://github.com/AlexxIT/SonoffLAN/issues/1871
9. https://github.com/AlexxIT/SonoffLAN/issues/1772
10. https://github.com/AlexxIT/SonoffLAN/issues/1907
11. https://github.com/AlexxIT/SonoffLAN/issues/1759
12. https://github.com/jcarcaboso/sonoff-local
13. https://github.com/CoolKit-Technologies/eWeLink-API/blob/main/en/APICenterV2.md
14. https://dev.ewelink.cc/
15. https://github.com/itead/Sonoff_Devices_DIY_Tools
16. https://developer.android.com/reference/android/net/nsd/NsdManager
17. https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock
18. https://tech.scargill.net/the-brand-new-sonoff-iplug-s60tpf-type-f-e-wifi-smart-plug/

Значна частина посилань — до одного проєкту SonoffLAN, а не 18 незалежних підтверджень. У разі суперечностей пріоритет: конкретна прошивка реальної S60TPF, офіційні описи виробника та перевірені трасування LAN із цієї моделі.
