# PDF Watcher — власний PDF-переглядач для Samsung (Android)

Нативний Kotlin, без AndroidX і сторонніх бібліотек: рендер — системний `PdfRenderer` (pdfium).
APK ~130 КБ, minSdk 30 (Android 11), пошук і паролі — лише Android 15+ (API 35).
Ставиться файлом APK (не через Play). Почато 2026-10-14.

## Що вміє
- Відкриває PDF звідусіль (Telegram, Gmail, «Мої файли», Chrome): `ACTION_VIEW`/`ACTION_SEND` `application/pdf`.
- Безперервна вертикальна прокрутка, щипок 1–8×, подвійний тап 2.5×, фліпінг, швидкий скролер з номером.
- Чіткість при зумі: окремий потік домальовує видиму ділянку на поточному масштабі (`PdfDoc` детальні тайли).
- Нічний режим (інверсія зі збереженням відтінків), «Перейти до сторінки», пошук з підсвіткою (API 35).
- Зберегти сторінку / усі / діапазон як PNG чи JPG (150/300 DPI) → `Pictures/PDF Watcher/<документ>`, «Поділитися».
- Поділитися PDF, друк, «Про файл», запаролені PDF (API 35).
- Нещодавні з мініатюрами й місцем читання. Файли з тимчасовим доступом (Telegram тощо) копіюються в
  `filesDir/docs` (до 300 МБ, останні 30), інакше їх не відкрити повторно.

## Збірка
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```
SDK: `/opt/homebrew/share/android-commandlinetools` (brew cask), AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21.
Перед кожною новою видачею піднімати `versionCode` в `app/build.gradle.kts`.

## Ключ підпису — НЕ ВТРАТИТИ
`release.keystore` + `keystore.properties` (пароль) у корені проєкту. Оновлення ставиться поверх
старої версії лише з тим самим ключем; новий ключ = видаляти застосунок і втратити «нещодавні».

## Тест
Емулятор `pdfw35` (Android 15, arm64). Тестові PDF генерує скрипт у scratchpad (pypdf) — за потреби
написати наново: багатосторінковий з латинським текстом, альбомна й висока сторінки, запаролений (1234).
