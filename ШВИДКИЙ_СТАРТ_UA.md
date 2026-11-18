# Сонoff S60 RC2 — швидкий старт

1. Збираємо APK через GitHub Actions: `Code` → `Actions` → `Build Android debug APK` → успішна збірка → `Artifacts` → архів із `app-debug.apk`.
2. Встановлюємо APK на Android. Телефон і справжня Wi-Fi S60TPF мають бути в одній Wi-Fi-мережі; LAN Control у eWeLink увімкнено.
3. Відкриваємо APK: пошук починається **автоматично**, адресу IP вводити не потрібно.
4. Під час **першого** підключення зашифрованої S60TPF потрібен унікальний `devicekey`, отриманий самим власником через авторизовану інтеграцію eWeLink. Зберігається Android Keystore. Після цього повторного вводу не потрібно.
5. Перевіряємо ON/OFF, показники потужності, струму, напруги та енергії; додаткові налаштування індикатора/стану після живлення з’являться, якщо їх справді передає розетка.
6. При помилці відкриваємо **Допомога** → `Поділитися журналом` та перевіряємо конкретні підказки.

## Заміна коду в існуючому GitHub-репозиторії

Розпакувати ZIP у папку вже наявного `C:\sonoff` із заміною файлів (не видаляти каталог `.git`). Відкрити термінал у `C:\sonoff`:

```powershell
git rm --cached --ignore-unmatch app/app-debug.apk
git add -A
git commit -m "Sonoff S60 RC2 LAN reliability and diagnostics"
git pull --rebase origin main
git push origin main
```

Якщо виникне `CONFLICT`, зупинитися, не виконувати `push`, надати лог; `--force` не використовувати.

## Для лабораторного емулятора (лише для тестів)

```powershell
py -m pip install cryptography
py tests\fake_s60.py --host 0.0.0.0 --port 8081 --advertise --advertise-ip 192.168.0.102 --devicekey lab-test-key-only
```

`192.168.0.102` замінити на **актуальний IP комп'ютера** із `ipconfig`. Це не підтверджує сумісність із реальною прошивкою S60.
