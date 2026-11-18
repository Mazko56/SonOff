$ErrorActionPreference = 'Stop'
Write-Host 'Requires Gradle 8.9, JDK 17 and Android SDK API 35 installed.'
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) { throw 'Gradle not found. Open folder in Android Studio (Build > Build APK) or use GitHub Actions.' }
if (-not $env:ANDROID_HOME) { Write-Warning 'ANDROID_HOME is not set. Android Studio can configure SDK automatically.' }
gradle --no-daemon assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Write-Host 'APK: app/build/outputs/apk/debug/app-debug.apk'
