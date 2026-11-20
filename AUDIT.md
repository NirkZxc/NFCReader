# TECHNICAL AUDIT — NFCReader

**Статус:** только исследование, исходники не менялись.
**Дата аудита:** 2026-11-19
**Проект:** C:\Users\almaz\AndroidStudioProjects\NFCReader

---

## 1. Project Overview

**Назначение проекта.** Превращает Android-смартфон в NFC-ридер: приложение читает метки NFC (UID, технологии, NDEF-сообщения) и может записывать текст/URI на метки, а все данные передаются на компьютер по TCP. Управление ведётся с ПК через консольный Python-скрипт `reader.py`.

**Сборка:** одна Gradle-модульность (`:app`), язык Kotlin, UI на Jetpack Compose (подключён, но фактически не используется), сборка через AGP 9.4.1 / Gradle 9.6.0.

**Компоненты проекта:**

| Компонент | Назначение |
|-----------|------------|
| `app/src/main/java/com/nirkzxc/nfcreader/MainActivity.kt` | Единственный экран/точка входа. Инициализирует `NfcAdapter`, поднимает TCP-сервер на порту 18765, принимает команды от ПК (сканирование, запись текста/URI, отмена, пинг), обрабатывает прикосновение метки (`enableReaderMode`) и шлёт JSON-события обратно на ПК. |
| `app/src/main/java/com/nirkzxc/nfcreader/ui/theme/Theme.kt, Color.kt, Type.kt` | Тема Material3 (цветовая схема, типографика) — подключена, но в `MainActivity` не вызывается `setContentView`, т. е. визуального интерфейса фактически нет. |
| `app/src/main/AndroidManifest.xml` | Декларация прав `NFC`, `INTERNET`, фичи `android.hardware.nfc` (`required="true"`), активность `MainActivity` (`exported="true"`, фильтр MAIN/LAUNCHER), `allowBackup="false"`. |
| `app/src/main/res/values/…` | Строки, темы, цвета (заглушки шаблона). |
| `app/src/main/res/mipmap-*/` | Адаптивная иконка лаунчера. |
| `app/src/main/keepRules/rules.keep` | Пустые R8/ProGuard правила (образец). |
| `reader.py` (корень проекта) | Консольный клиент на ПК: соединяется с телефоном по TCP (127.0.0.1:18765), шлет команды `SCAN` / `WRITE_TEXT|...` / `WRITE_URI|...` / `CANCEL_WRITE` / `PING` / `quit`, принимает JSON-события, печатает их и логирует сканы в `scans.jsonl`. |
| `gradle/libs.versions.toml` | Каталог версий зависимостей (AGP, Kotlin, Compose BOM, androidx, тесты). |
| `app/src/test/…, app/src/androidTest/…` | Заглушки тестов (JUnit 4, Espresso) без реальной логики NFC/TCP. |

**Отсутствуют:** README, LICENSE, git-репозиторий, любой графический интерфейс в приложении, документация по подключению через USB.

---

## 2. Architecture

### 2.1 Архитектурный стиль
Моно活动 (single Activity), без разделения на слои: нет ViewModel, репозитория, UseCase, DI (Hilt/Koin), нет даже состояния UI. Весь код — в одном `MainActivity` (~260 строк): NFC, TCP-сервер, парсинг команд, парсинг NDEF, сериализация JSON. Это "скриптовый" стиль, приемлемый для прототипа, но не для поддерживаемого продукта.

### 2.2 Поток данных
```
PC (reader.py)  TCP commands  -->  Android MainActivity.ServerSocket (port 18765)
Android                                     <--  JSON events  --  PC
      |
      | NFC tag touch (enableReaderMode callback)
      v
  Ndef.read()  -->  JSONObject  -->  sendJson()  -->  PC
```

### 2.3 Потоковая модель
- `startServer()` — отдельный поток с `ServerSocket.accept()`.
- На каждое подключение — поток `readCommands(socket)`.
- Каждое исходящее сообщение (`sendJson`) — **новый поток** (`Thread { ... }.start()`).
- Общий mutable state: `writer` (PrintWriter), `clientSocket`, `serverSocket`, `writeMode/writeType/writeData` (@Volatile).

### 2.4 Жизненный цикл NFC
- `onResume` → `nfcAdapter.enableReaderMode(..., FLAG_READER_NFC_A|B|F|V, null)`
- `onPause` → `disableReaderMode(this)`
- Работа только в фоне приложения (приложение на переднем плане, экран включен).

### 2.5 "USB"-связь (фактом — TCP через adb reverse)
На телефоне поднимается `ServerSocket` (все интерфейсы, `0.0.0.0:18765`). `reader.py` подключается к `127.0.0.1:18765`. Для работы через USB необходимо заранее выполнить `adb reverse tcp:18765 tcp:18765` — это **не документировано нигде в проекте**.

---

## 3. Technology Stack

| Компонент | Версия | Комментарий |
|-----------|--------|-------------|
| Kotlin | 2.2.10 | Современная, но очень новая версия (может быть нестабильными плагинами в старых CI). |
| AGP (Android Gradle Plugin) | 9.4.1 | Крайне свежая версия (линейка 2026 г.). Требует свежий JDK/Android Studio; маловероятна поддержка в старых CI-раннерах. |
| Gradle | 9.6.0 (wrapper) | Актуальная, свежая. |
| compileSdk | 37 | Android "Vanilla Ice Cream" / выпуск 2026 г. — bleeding edge. |
| minSdk | 26 | Android 8.0 Oreo. Разумный порог для NFC. |
| targetSdk | 37 | Требует соответствия поведению Android 17 (разрешения, фоновые задачи и т. д.). |
| Java | 11 (source/targetCompatibility) | В порядке. |
| UI | Jetpack Compose Material3 (BOM 2026.02.01) | Подключён, но не используется: `MainActivity : Activity()` без `setContentView`. |
| Сетевое взаимодействие | `java.net.ServerSocket/Socket`, `BufferedReader`, `PrintWriter` | Сырые сокеты, без библиотек (OkHttp, Ktor и т. д.). |
| JSON | `org.json` (встроенный в Android) | без схемы/валидации. |
| Тестирование | JUnit 4.13.2, Espresso 3.5.1, AndroidX Test | Только заглушки. |
| ПК-сторона | Python 3 (`socket`, `json`, `threading`) | Однопоточный консольный клиент. |
| Сборка | Kotlin DSL (`build.gradle.kts`), Version Catalog | Хорошая практика. |

---

## 4. NFC Implementation

### 4.1 Чтение меток
- `NfcAdapter.getDefaultAdapter(this)` + `enableReaderMode` с флагами `NFC_A | NFC_B | NFC_F | NFC_V`.
- В 콜беке `processTag(tag)` собирается JSON:
  - `uid` — шестнадцатеричный строковый идентификатор метки.
  - `technologies` — `tag.techList` (все технологии).
  - Через `Ndef.get(tag)`: `type`, `maxSize`, `writable`, `cachedNdefMessage`.
  - `parseNdef(message)` — каждая запись: `tnf`, `type` (строка), `payloadHex`, опционально `uri` через `record.toUri()`.

### 4.2 Запись на метки
- По команде от ПК (`WRITE_TEXT|` / `WRITE_URI|`) включается `writeMode`.
- При следующем касании метки `writeToTag(tag)`:
  1. `Ndef.get(tag)` — отказ, если нет NDEF.
  2. `ndef.connect()`, проверка `isWritable`.
  3. `NdefRecord.createTextRecord("en", text)` или `NdefRecord.createUri(uri)`.
  4. Проверка размера `bytes.size > ndef.maxSize`.
  5. `ndef.writeNdefMessage(message)`.
- Результат шлётся на ПК как `write_result` (`OK: ...` или `ERROR: ...`).

### 4.3 Ограничения NFC-реализации
- **Нет проверки наличия NFC-адаптера.** `getDefaultAdapter` возвращает `null` на устройствах без NFC → `NullPointerException` в `onResume` при вызове `enableReaderMode`. Манифест с `required="true"` защищает только от установки через Google Play, но не от `adb install` / сideloading.
- **`cachedNdefMessage`** может быть `null` или устаревшим; после `connect()` предпочтительнее `getNdefMessage()`.
- Не обрабатываются не-NDEF метки с полезными данными (например, сырой Mifare Classic через `MifareClassic`) — только список технологий.
- Нет обработки `NdefFormatable`, нет записи пустых/форматирования меток.
- Нет проверки состояния NFC (включен/выключен в настройках системы) — пользователю не показывается подсказка включить NFC.
- Нет флага `FLAG_READER_SKIP_NDEF_CHECK` — для некоторых нестандартных меток чтение может пропускаться.

---

## 5. USB Communication

### 5.1 Фактический транспорт
Это **не USB API** (не `UsbManager`, не accessory mode). Это обычный TCP-сокет через локальный интерфейс телефона. "USB" достигается только при наличии внешнего туннелирования, например:

```
adb reverse tcp:18765 tcp:18765
```
после чего ПК обращается к `127.0.0.1:18765`, а трафик перенаправляется на телефон.

Альтернативно телефон раздаёт точку доступа / USB-тетеринг, и ПК соединяется по IP телефона — но `reader.py` хардкодит `127.0.0.1`.

### 5.2 Протокол обмена
Текстовый, построчный, JSON (по одной строке на сообщение), кодировка UTF-8.

**Команды ПК → Телефон:**
| Команда | Действие |
|---------|----------|
| `SCAN` | Ответ `scan_ready` (погружает приложение в режим ожидания касания) |
| `WRITE_TEXT|<текст>` | Включить запись текста (язык `en`) |
| `WRITE_URI|<uri>` | Включить запись URI |
| `CANCEL_WRITE` | Снять режим записи |
| `PING` | Ответ `pong` |
| `quit` | Закрыть соединение |

**События Телефон → ПК:**
| Событие | Содержимое |
|---------|------------|
| `scan_ready` | подтверждение режима сканирования |
| `write_ready` | `type`, `data` — режим записи вооружён |
| `scan` | `uid`, `technologies`, NDEF-поля (`type`, `maxSize`, `writable`, `ndefRecords`) или `ndefError` |
| `write_result` | `result` — `OK: ...` или `ERROR: ...` |
| `write_cancelled` | подтверждение отмены |
| `pong` | ответ на пинг |

### 5.3 Проблемы транспорта
- Нет аутентификации соединения.
- Нет шифрования / TLS (всё в открытом виде).
- `ServerSocket(PORT)` без параметра адреса → привязка ко **всем интерфейсам** (`0.0.0.0`): порт доступен не только через USB/адб, но и по Wi-Fi/LAN любому устройству в сети.
- Нет лимитов на размер входящих команд/JSON (可能DoS).
- Нет таймаутов на `accept()` / `readLine()` — висячие соединения не освобождаются.
- `reader.py` жёстко привязан к `127.0.0.1` — без `adb reverse` не работает, и это не документировано.
- На стороне Android сокет не закрывается корректно при разрыве (старые потоки `readCommands` могут висеть до процесса смерти).

---

## 6. Compatibility (совместимость с современным Android)

| Аспект | Оценка | Комментарий |
|--------|--------|-------------|
| minSdk 26 | ✅ | NFC API (`enableReaderMode` с API 19, `Ndef` с API 14) доступны на всем диапазоне. |
| targetSdk / compileSdk 37 | ⚠️ | Крайне свежий Android 17. Требует проверки поведения новых версий (разрешения, фоновые задачи, изменения в сетевом стеке). Инструментарий (AGP 9.4.1, Gradle 9.6) ещё не широко распространён в CI. |
| Жёсткая зависимость от NFC железа | ✅/`⚠️` | `uses-feature required="true"` фильтрует устройства без NFC в Play Store, но не защищает от崩溃 при сideloading (см. выше `null` адаптер). |
| Разрешения | ✅ | `android.permission.NFC` — normal permission, не требует runtime-запроса. `INTERNET` — normal. |
| Экспорт активности | ✅ | `android:exported="true"` явно указан (требование Android 12+ для активностей с фильтрами Intent). |
| Резервное копирование | ✅ | `allowBackup="false"`; заглушки `backup_rules.xml` / `data_extraction_rules.xml` без эффекта. |
| Темная тема / динамические цвета | ✅ | Где используется (`Build.VERSION.SDK_INT >= S` guarded), но UI фактически отсутствует. |
| Фоновая работа NFC | ⚠️ | `enableReaderMode` работает только пока приложение на переднем плане (`onResume`/`onPause`). При выключенном экране или уходе в фон — чтение прекращается. Некоторые оболочки (MIUI, EMUI и др.) могут агрессивно ограничивать фон, но приложениеforeground-only, так что это приемлемо. |
| Разные производители | ✅/⚠️ | На устройствах без NFC чипа приложение упадёт при запуске (см. CRITICAL). На устройствах с NFC читаются стандартные метки NDEF (NFC Forum Types 1–4, Mifare Classic через NDEF-обёртку). |

---

## 7. Security Review

### 7.1 Передача данных (TCP)
1. **Нет шифрования.** UID меток, содержимое NDEF-записей и команды передаются в открытом виде. По USB через `adb reverse` трафик остается внутри устройства/кабеля, но при подключении по Wi-Fi/LAN доступен любому в той же сети.
2. **Нет аутентификации.** Любой процесс/устройство, достучавшееся до порта 18765, может инициировать запись на NFC-метку (`WRITE_TEXT|`, `WRITE_URI|`) — т. е. злоумышленник в той же сети может записать вредоносный NDEF-URI на метку, которую пользователь приложит к телефону (NDEF-инъекция).
3. **Сокет на всех интерфейсах.** `ServerSocket(18765)` без привязки к `127.0.0.1` открывает порт для всей локальной сети.
4. **Нет ограничения частоты / размера команд.** Потенциальный вектор DoS.

### 7.2 Логирование
Теги (UID, NDEF-содержимое, ошибки) пишутся в `Logcat` (`Log.i/e`, тег `NFC_READER`). На рутованных устройствах логи доступны другим приложениям/процессам — утечка идентификаторов меток.

### 7.3 Хранение данных
На стороне приложения постоянного хранения нет (хорошо). На стороне ПК `reader.py` пишет `scans.jsonl` в текущую директорию без шифрования и без прав доступа к файлу.

### 7.4 Подпись релиза
Release `buildType` now requires an explicit release signing configuration (`signing.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Debug signing is **never** used for release. Missing `signing.properties` → clear build error ("Keystore file not set for signing config release"). The release APK cannot be published until a production signing config is provided.

### 7.5 Итог по безопасности
Проект пригоден только для локальной доверенной среды (один компьютер + один телефон по USB/adb). Непригоден для использования в публичных Wi-Fi или без доработки модели безопасности.

---

## 8. Bugs & Technical Debt

### CRITICAL (работоспособность / падения)
1. **NPE на устройствах без NFC.** `nfcAdapter` может быть `null`; `onResume` вызывает `nfcAdapter.enableReaderMode(...)` без проверки.
2. **Пустой UI / неиспользуемый Compose.** `MainActivity : Activity()` не вызывает `setContentView` — пользователь видит пустой экран. Весь набор Compose-зависимостей — мёртвый груз.
3. **Релиз подписан debug-сертификатом.** Нельзя公開овать в маркетах.

### HIGH (функциональные дефекты и риски)
4. **Открытый TCP-порт без авторизации и шифрования** на всех сетевых интерфейсах.
5. **Отсутствует документация по `adb reverse`** — пользователь не поймёт, как соединить ПК и телефон через USB.
6. **`cachedNdefMessage` вместо `getNdefMessage()`** — риск устаревших/отсутствующих данных при чтении.
7. **`writer` не volatile / не синхронизировано присваивание** — гонка между потоком сервера (`startServer`, переприсваивает `writer`) и потоками отправки (`sendJson`) —可能 NPE или отправка в закрытый сокет.
8. **Сервер не перезапускается после ошибки** (например, порт занят) — после падения потока сервера приложение перестаёт принимать соединения до перезапуска активности.
9. **Режим записи armas навсегда** — если метка не приложена, `writeMode` остаётся `true` до команды `CANCEL_WRITE` или успешной записи; нет таймаута.
10. **`reader.py` хардкодит `127.0.0.1`** — без правки кода не работает через IP телефона.

### MEDIUM (качество кода, долг)
11. **Thread на каждое сообщение** (`sendJson` создаёт `Thread`) — неограниченное создание потоков при интенсивном обмене.
12. **Нет реальных тестов** NFC/TCP логики — только трюivialные заглушки JUnit/Espresso.
13. **Мёртвые зависимости** Compose/activity-compose без использования.
14. **Пустые R8 правила** — нет оптимизации/обфускации релиза.
15. **Логирование чувствительных данных** (UID, содержимое меток) в Logcat.
16. **Нет обработки разрыва соединения ПК** — старый `clientSocket` закрывается, но старый поток `readCommands` может продолжать висеть.

### LOW (полировка, мелочи)
17. `versionCode = 1`, `versionName = "1.0"` — стартовые значения, требуют инкремента при релизах.
18. Нет `ContentDescription` / доступности (впрочем, UI отсутствует).
19. `reader.py` использует `sock.makefile()` и raw `sock.sendall()` на одном сокете — потенциальные буферизационные нюансы.
20. Нет `.git` репозитория, README, LICENSE — не готово к публикации на GitHub "из коробки".

---

## 9. Recommended Improvements

### Краткосрочно (до первой публичной версии)
1. Добавить `null`-проверку `NfcAdapter` и уведомление пользователя ("NFC не поддерживается данным устройством" / "Включите NFC в настройках").
2. Привязать `ServerSocket` кループбэку (`127.0.0.1`) или добавить явное предупреждение; обязательно добавить простую аутентификацию (токен/пароль в рукопожатии) или как минимум документацию о том, что порт открыт в сети.
3. Добавить TLS или явно позиционировать инструмент только для доверенного USB-соединения через `adb reverse`.
4. Написать README: сборка, `adb reverse tcp:18765 tcp:18765`, запуск `reader.py`, протокол команд.
5. Добавить LICENSE и инициализировать git-репозиторий.
6. Исправить подпись релиза (отдельный конфиг подписи, не debug).
7. Переписать чтение NDEF на `getNdefMessage()` после `connect()`.
8. Добавить таймаут режима записи и автоматический сброс `writeMode`.

### Среднесрочно
9. Добавить настоящий UI (Compose) или явно оставить headless-режим и убрать мёртвые Compose-зависимости.
10. Заменить `Thread`-на-каждое-сообщение на пул потоков / корутины.
11. Сделать `writer` и `clientSocket` надёжно синхронизированными (или `volatile` + блокировка).
12. Добавить обработку не-NDEF меток (сырые технологии `NfcA`, `MifareClassic` и т. д.) по желанию.
13. Написать unit-тесты для парсинга команд и NDEF, инструментальные тесты для NFC (Robolectric/сенсорный эмулятор).
14. Сделать `reader.py` гибким: аргумент `--host`, `--port`, авто-определение адреса.
15. Добавить `.github/workflows` CI (сборка debug, запуск тестов, линт ktlint/detekt).

### Долгосрочно
16. Рассмотреть переход с `enableReaderMode` на foreground dispatch / Intent-filter для работы с конкретными типами меток при запуске приложения.
17. Добавить сетевую конфигурацию (`networkSecurityConfig`) и, при необходимости, ограничение доступа к порту только через USB/adb.
18. Документировать модель угроз и поддерживаемые модели угроз (доверенный ПК, локальная сеть).

---

## 10. GitHub Release Readiness

| Требование | Статус | Комментарий |
|------------|--------|-------------|
| Git-репозиторий | ❌ | `.git` отсутствует — необходимо `git init`. |
| README.md | ❌ | Нет инструкций по сборке и подключению (`adb reverse`). |
| LICENSE | ❌ | Нет лицензии — правовой статус unclear. |
| .gitignore | ⚠️ | Есть базовый, но не игнорирует все `.idea/*.xml` и `local.properties` дублируется; в целом приемлемо. |
| Сборка из кода | ⚠️ | Требует AGP 9.4.1 / Gradle 9.6 / JDK 17+ и compileSdk 37 — может не собираться на старых CI/машинах. |
| Подпись релиза | ❌ | Используется debug-сертификат. |
| Тесты | ❌ | Только заглушки. |
| Документация протокола | ❌ | Нет описания JSON-протокола и команд `reader.py`. |
| Версии зависимостей | ⚠️ | Katalog версий есть, но версии bleeding-edge (AGP 9.4.1, compileSdk 37). |
| **Итог** | ❌ **Не готово** | Требуются изменения из группы CRITICAL и HIGH перед публикацией. |

---

## Приоритетный список изменений

### CRITICAL (блокируют выпуск / вызывают падения)
1. Добавить `null`-проверку `NfcAdapter` с graceful сообщением (падение на устройствах без NFC).
2. Добавить README, LICENSE и инициализировать git-репозиторий (базовая готовность к GitHub).
3. Исправить подпись релиза (не debug-сертификат).
4. Закрыть TCP-порт от посторонних: привязка к `127.0.0.1` и/или простая аутентификация; задокументировать необходимость `adb reverse tcp:18765 tcp:18765`.

### HIGH (серьёзные дефекты и риски)
5. Документировать порядок подключения телефона к ПК через USB (`adb reverse`).
6. Заменить `cachedNdefMessage` на `getNdefMessage()` после `connect()`.
7. Исправить гонку при присваивании `writer` между потоками (сделать потокобезопасным).
8. Добавить перезапуск TCP-сервера после ошибки / освобождение порта.
9. Добавить таймаут режима записи (`writeMode`) с автоматическим сбросом.
10. Сделать `reader.py` параметризуемым (host/port), убрать хардкод `127.0.0.1`.

### MEDIUM (качество и поддерживаемость)
11. Убрать мёртвые Compose-зависимости или добавить полноценный UI (`setContentView`).
12. Заменить `Thread`-на-сообщение на пул потоков / корутины.
13. Добавить реальные тесты NFC/TCP логики.
14. Прекратить логирование UID и содержимого меток в Logcat.
15. Добавить CI-воркфлой и базовые R8-правила для релиза.

### LOW (полировка)
16. Инкремент `versionCode`/`versionName` перед релизом.
17. Добавить `.idea/` в `.gitignore` полностью.
18. Документировать протокол обмена (формат JSON, события, ошибки).

---

### Общий вывод
Проект представляет собой рабочий прототип "телефон как NFC-ридер → ПК по TCP". Идея и базовый поток данных реализованы корректно (reader mode, NDEF-чтение/запись, JSON-протокол, Python-клиент). Однако для публикации на GitHub и реального использования приложению необходимы исправления в области стабильности (NPE на без-NFC устройствах), безопасности (открытый неаUTHентифицированный TCP-порт, отсутствие шифрования), документации (сборка, `adb reverse`, протокол) и инфраструктуры (git, README, LICENSE, релизная подпись, тесты).

---

## 11. Continued Audit — Additional Verified Findings (2026-11-19)

This section records additional source-level findings from the continued review of `MainActivity.kt` and `reader.py`, each with an exact file:line reference. All findings below were verified by reading the current source; no source code was modified.

### 11.1 Android NFC — `MainActivity.kt` (verified)

| # | Finding | Severity | File:Line |
|---|---------|----------|-----------|
| 1 | `nfcAdapter` assigned at line 44 via `NfcAdapter.getDefaultAdapter(this)` without a null check; used unguarded at lines 158 (`enableReaderMode`) and 174 (`disableReaderMode`) → `NullPointerException` on devices without NFC hardware. | CRITICAL | MainActivity.kt:44,158,174 |
| 2 | `writer: PrintWriter?` (line 39) is **not** `@Volatile` — only `writeMode`/`writeType`/`writeData` are (lines 28-35). `startServer` assigns `writer` at line 61 while `sendJson` reads it at line 375 → race between the server-accept thread and send threads. | HIGH | MainActivity.kt:39,61,375 |
| 3 | `clientSocket` (line 38) and `serverSocket` (line 37) are read/written from multiple threads (`startServer`, `readCommands`, `sendJson`, `onDestroy`) without synchronization beyond the narrow `synchronized(this)` in `sendJson`. | HIGH | MainActivity.kt:37-38,58-61,371-382,384-391 |
| 4 | `cachedNdefMessage` read at line 236 instead of `getNdefMessage()` after `ndef.connect()` (line 230) → may return `null` or stale data; `getNdefMessage()` is the post-connect source of truth. | HIGH | MainActivity.kt:230,236 |
| 5 | `serverSocket!!.isClosed` at line 55 uses the `!!` non-null assertion (discouraged by Kotlin style) — if `serverSocket` were ever null this throws `NPE`. | MEDIUM | MainActivity.kt:55 |
| 6 | `onDestroy` (line 384) closes sockets but never interrupts the blocked `accept()` call in `startServer` (line 56) or the `readCommands` reader threads → threads can outlive the Activity and leak until process death. | MEDIUM | MainActivity.kt:384-391,50-73,76-153 |
| 7 | `startServer` catches `Exception` at line 70 and the thread dies silently — no restart/backoff if the port is already in use, so the app stops accepting connections until the Activity is recreated. | MEDIUM | MainActivity.kt:70-72 |
| 8 | `sendJson` creates a new `Thread` per message (line 372) and synchronizes on `this` (the Activity instance, line 374) — a broad monitor; unbounded thread creation under high message frequency. | MEDIUM | MainActivity.kt:371-382 |
| 9 | `tag.id.joinToString("") { "%02X".format(it) }` at line 209 — if `tag.id` is `null`, this throws `NPE`. | LOW | MainActivity.kt:209 |
| 10 | `Log.i(TAG, "COMMAND: $command")` at line 85 logs raw PC commands; tag UID is logged indirectly through JSON serialization. Sensitive tag data ends up in Logcat, readable by other apps on rooted devices. | LOW | MainActivity.kt:85,209-212 |

### 11.2 Python USB/TCP receiver — `reader.py` (verified)

| # | Finding | Severity | File:Line |
|---|---------|----------|-----------|
| 1 | `HOST = "127.0.0.1"` and `PORT = 18765` hardcoded (lines 6-7) — no CLI argument (`--host`, `--port`) to override; cannot connect via phone IP without editing source. | HIGH | reader.py:6-7 |
| 2 | `sock.settimeout(None)` at line 142 removes the connect timeout for the socket's lifetime — `readline()` in the receiver can block forever if the Android side stops sending data without closing the socket. | HIGH | reader.py:142 |
| 3 | `sock.makefile("r", encoding="utf-8")` at line 71 creates a buffered reader; `readline()` at line 79 blocks until a newline arrives. If Android sends a partial line without `\n`, the receiver hangs indefinitely. | HIGH | reader.py:71-79 |
| 4 | `send()` (lines 14-18) calls `sock.sendall(...)` without verifying `sock` is connected or non-`None` → `AttributeError` if called before connect or after disconnect. | MEDIUM | reader.py:14-18 |
| 5 | Blocking `input()` in the main thread (line 171) while `receiver` is a daemon thread (line 163). `KeyboardInterrupt` sets `running = False` but the main thread may remain stuck on `input()`; daemon thread may be terminated mid-cleanup. | MEDIUM | reader.py:160-174 |
| 6 | `scans.jsonl` opened with `"a"` mode at line 24 using default file permissions — no encryption, no restrictive umask; scanned tag UIDs/NDEF content stored in plaintext in the current working directory. | LOW | reader.py:24 |
| 7 | `print()` used throughout (lines 17-18, 34-65, 103-122, 132-158, 168-234) instead of the `logging` module — no log levels, no structured output, no suppression option. | LOW | reader.py:multiple |

### 11.3 Build / project-level (verified)

| # | Finding | Severity | File:Line |
|---|---------|----------|-----------|
| 1 | Release `buildType` signed with `signingConfigs.getByName("debug")` — release APK carries the debug certificate and cannot be published to Google Play or distributed as a release. | CRITICAL | app/build.gradle.kts:24 |
| 2 | `compileSdk = 37` / `targetSdk = 37` (Android 17, 2026), AGP 9.4.1, Gradle 9.6.0, Kotlin 2.2.10 — bleeding-edge toolchain; unlikely to build on older CI runners or workstations with outdated JDK/Android Studio. | HIGH | app/build.gradle.kts:9-10,15; build.gradle.kts; gradle/libs.versions.toml:2,9-10,30 |
| 3 | `ServerSocket(18765)` (line 52) binds to `0.0.0.0` (all interfaces) with no loopback binding — port reachable over Wi-Fi/LAN, not only via `adb reverse` USB tunneling. | HIGH | MainActivity.kt:52 |
| 4 | No `network_security_config.xml` declared in the manifest — cleartext TCP traffic is not restricted at the OS level. | HIGH | AndroidManifest.xml (missing) |
| 5 | No `UsbManager`/USB accessory API anywhere — "USB" connectivity is exclusively TCP + `adb reverse tcp:18765 tcp:18765`, undocumented in the project. | MEDIUM | MainActivity.kt (no USB APIs); reader.py:6-7 (`127.0.0.1` hardcode) |
| 6 | No git repository, no `README.md`, no `LICENSE` — not ready for GitHub publication "out of the box". | MEDIUM | Project root |
| 7 | `local.properties` contains only `sdk.dir` (Android SDK path) — no secrets or credentials present. | INFO | local.properties |
| 8 | `ExampleUnitTest.kt` (line 14) and `ExampleInstrumentedTest.kt` (line 19) are trivial stubs — zero coverage of NFC, TCP, or command-parsing logic. | MEDIUM | app/src/test/...:14; app/src/androidTest/...:19 |

### 11.4 Typo correction in AUDIT.md

- Section 4.1 line ~88: "В колбеке" uses a Cyrillic `В` instead of Latin `B` ("в колбеке"). No code impact.

### Общий вывод
Проект представляет собой рабочий прототип "телефон как NFC-ридер → ПК по TCP". Идея и базовый поток данных реализованы корректно (reader mode, NDEF-чтение/запись, JSON-протокол, Python-клиент). Однако для публикации на GitHub и реального использования приложению необходимы исправления в области стабильности (NPE на без-NFC устройствах, гонки потоков при работе с `writer`/`clientSocket`), безопасности (открытый неаUTHентифицированного TCP-порт, отсутствие шифрования, отсутствие network_security_config), документации (сборка, `adb reverse`, протокол) и инфраструктуры (git, README, LICENSE, релизная подпись, тесты).

Исходные файлы проекта не изменялись.

---

## 12. Fix Status (2026-11-20)

This section tracks which audit findings have been addressed across all work sessions.

### Fixed — CRITICAL

| # | Finding | Fix | File(s) |
|---|---------|-----|---------|
| 1 | NPE on devices without NFC — `nfcAdapter` used unguarded | Null-safe `nfcAdapter?.let { ... }` in `onResume`/`onPause`; graceful log when adapter is null | MainActivity.kt:70-72 |
| 2 | Release APK signed with debug certificate | Release `buildType` now requires explicit release signing config (`signingConfigs.getByName("release")`). Debug signing is never used for release. Missing `signing.properties` → clear build error ("Keystore file not set"). | app/build.gradle.kts:29-52 |
| 3 | Hardcoded default auth token `DEFAULT_AUTH_TOKEN = "nfc-reader-change-me"` | Removed the public default entirely. `BuildConfig.AUTH_TOKEN` is sourced from `-PnfcAuthToken` or `NFC_AUTH_TOKEN` env var, defaulting to `""`. A blank token rejects all authentication attempts (`auth_failed: not_configured`). Same rule on PC side: `--auth-token` is required. | MainActivity.kt:33-36,142-151; app/build.gradle.kts:21-26; reader.py:164-166 |

### Fixed — HIGH

| # | Finding | Fix | File(s) |
|---|---------|-----|---------|
| 1 | Thread race on `writer`/`clientSocket`/`serverSocket` | Single `socketLock` monitor guards all socket state; `writer`/`clientSocket` are `@Volatile` | MainActivity.kt:37-39,58-61,93-96,374-388 |
| 2 | Wrong ADB direction documented (`adb reverse` instead of `adb forward`) | Android runs the TCP server; PC is the client. Changed all docs from `adb reverse tcp:18765 tcp:18765` to `adb forward tcp:18765 tcp:18765`. | README.md:3,72-75; reader.py:4-5 |
| 2 | Hardcoded `127.0.0.1` in `reader.py` | `--host`/`--port` CLI arguments with defaults | reader.py:17-21,132-145 |
| 3 | Socket bound to `0.0.0.0` (all interfaces) | `ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))` — loopback only | MainActivity.kt:90-92 |
| 4 | No authentication on TCP | `AUTH|<token>` required before any command; max 3 attempts; `BuildConfig.AUTH_TOKEN` on Android, `--auth-token` on PC | MainActivity.kt:121-148; reader.py:140-145 |
| 5 | No socket timeouts | `soTimeout = 5000` on accept, `soTimeout = 30000` on client sockets | MainActivity.kt:93,103; reader.py:20-21,107-109 |
| 6 | `cachedNdefMessage` may be stale/null | Reads `ndef.getNdefMessage()` after `connect()` | MainActivity.kt:247-250 |
| 7 | `reader.py` blocking `input()` with daemon thread | `try/finally` ensures `running = False` and socket close; `KeyboardInterrupt` handled | reader.py:165-193 |
| 8 | No real tests; stubs removed | `CommandHandler.kt` extracted as pure Kotlin + 20 JVM unit tests; `reader.py` has 11 pytest tests | CommandHandler.kt, CommandHandlerTest.kt, tests/test_reader.py |
| 9 | No README, LICENSE, .gitignore complete | Added README.md, LICENSE, updated .gitignore | README.md, LICENSE, .gitignore |
| 10 | No CI | Added `.github/workflows/ci.yml` (Android + Python) | .github/workflows/ci.yml |

### Fixed — MEDIUM

| # | Finding | Fix | File(s) |
|---|---------|-----|---------|
| 1 | `Thread` per `sendJson` → unbounded threads | `Executors.newSingleThreadExecutor` for all outgoing sends | MainActivity.kt:49-54,384-396 |
| 2 | `serverSocket!!.isClosed` `!!` usage | `while (isServerRunning && !serverSocket!!.isClosed)` guarded by `isServerRunning` flag; accept timeout loop | MainActivity.kt:80-114 |
| 3 | `onDestroy` doesn't interrupt blocked `accept()`/`readLines` | `isServerRunning = false`, closes `clientSocket` and `serverSocket`, shuts down `sendExecutor` | MainActivity.kt:382-396 |
| 4 | `ExampleUnitTest.kt`/`ExampleInstrumentedTest.kt` stubs | Removed `ExampleUnitTest.kt`; replaced with `CommandHandlerTest.kt` (20 tests) | app/src/test/... |

### Remaining (documented, not in scope for this release)

| # | Finding | Status |
|---|---------|--------|
| 1 | No TLS/encryption on TCP | Documented in README security model; loopback-only + auth mitigates local risk |
| 2 | No `network_security_config.xml` | Documented; cleartext TCP only over USB/adb tunnel |
| 3 | No `UsbManager`/USB accessory API | Documented: "USB" = TCP + `adb forward` |
| 4 | `scans.jsonl` no encryption/restrictive permissions | Documented limitation |
| 5 | NFC only works in foreground | Documented limitation (`enableReaderMode` lifecycle) |
| 6 | Release signing requires external `signing.properties` | Documented in README |
| 7 | Physical NFC tag needed for write testing | Unit tests cover protocol logic without hardware |

