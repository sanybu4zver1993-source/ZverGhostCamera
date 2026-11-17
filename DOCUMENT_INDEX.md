# DOCUMENT_INDEX.md — Единый реестр документации ZVER CAMERA

> **Проект:** `Zver Camera` (`applicationId = "com.aistudio.ghostsuite.vkxpqz"`, `namespace = "com.example"`)  
> **Целевое устройство:** Xiaomi Redmi 13C (`23108RN04Y`), Android 15 (API 35, HyperOS 2.0.209), без root  
> **Статус аудита:** Базовая ревизия кода, склеенного манифеста (`processDebugManifest`), дерева зависимостей (`debugRuntimeClasspath`) и 25 локальных unit/Robolectric-тестов завершена.

---

## 1. Реестр базовых документов (Unified Baseline)

Все перечисленные документы образуют единый комплект проектной документации. В случае противоречий действуют явные исключения из `DOC-004` и фактическая верификация по исходному коду и `merged_manifests`.

| ID | Документ / Источник | Роль в проекте | Статус актуальности |
|---|---|---|---|
| **DOC-001** | Начальное ТЗ: Ghost Camera / Vault / EXIF Stripper / Камуфляж | Базовое ядро камеры, шифро-контейнера `.gcf`, удаления EXIF и смены иконки | Частично устарело в части `INTERNET` (добавлен opt-in BYOK) |
| **DOC-002** | Дополнение №1: MonoVault v4.1, Anti-PRNU, MP4 Sanitizer, PanicKit, CyberVisor v1 | Усиление форензик-защиты, стегано-контейнер 1 ГБ, видео-санитайзер, физическая защита | Реализовано с оговорками (см. классификацию) |
| **DOC-003** | Дополнение №2: BYOK Gemini API (`Вика AI`), 6-NN локальный симбиоз ML Kit, видео-экстрактор | Клиентский ИИ по пользовательскому ключу + 6 локальных нейронок ML Kit | Выявлены P0-дефекты состояния/UI/моделей |
| **DOC-004** | Дополнение №3: Полировка UX/UI, жёсткие исключения и ограничения Redmi 13C | Исключение галлюцинаций, фейкового OSINT, Telegram-ботов и несуществующего NDK C++ | Действует как жёсткий фильтр архитектуры |
| **DOC-005** | Дополнение №4: Apex Sentinel v5.0 («Кибер-Дозор»), Cyber-Visor v2.1/v2.2, CameraX стабильность | Детектор зависания у порога, датчик стука, CLAHE, POS rPPG + AE/AWB Lock, Эйлер x20 | Частично не проверено на реальном HAL устройства |
| **DOC-006** | Внешние отчёты аудита (Qwen / Kimi) + результаты видеотестирования на Redmi 13C | Живые воспроизведения P0-багов на физическом Redmi 13C (30 файлов `.gcf`, камера, маскировка, UI, BYOK, GitHub Releases) | Подтверждено аудитом исходников и `merged_manifest` |
| **DOC-007** | **Технический паспорт ZVER Camera (Master Spec)** | Полное описание 10 модулей: железо Redmi 13C Helio G85, 8 режимов карусели, 11 режимов CyberVisor, AntiBlur датчики, 3-контурный Сейф + MonoVault, ExifStripper + Anti-PRNU, Калькулятор/Камуфляж, Офлайн Редактор Приватности и OSINT-линтер, 6-NN локальный симбиоз + Вика AI (BYOK), Termux/Shizuku автомизация и CI/CD | **Основной baseline проекта**, отражающий реальный стек и архитектуру |

---

## 2. Классификация функций из DOC-007 (Технический паспорт ZVER Camera)

Каждая заявленная функция классифицирована строго по одному из 6 допустимых состояний:
1. `Реализовано и подтверждено` — логика в коде полная, подтверждена unit/Robolectric-тестами или `merged_manifest`, без известных P0-дефектов.
2. `Реализовано частично` — каркас или часть логики есть, но не хватает интеграции, обвязки, персистенции или сквозного сценария.
3. `Реализовано, но имеет дефект` — код написан, но аудит исходников или тесты пользователя на устройстве доказали поломку/гонку/несовпадение контрактов.
4. `Присутствует только интерфейс` — есть UI-элемент, сервис-заглушка или текстовое описание без реального исполнения.
5. `Не реализовано` — в исходном коде отсутствует.
6. `Не проверено на физическом устройстве` — логика в коде присутствует и компилируется, но её работа на реальном железе Redmi 13C (HAL, сенсоры, ОС) требует живой проверки или замеров (все числовые тайминги/FPS без живого профайлинга считаются `UNVERIFIED`).

### 2.1. Базовая платформа, сборка и системная интеграция

| Функция / Заявление из DOC-007 | Фактическая реализация в коде | Классификация по состоянию | Комментарий аудита |
|---|---|---|---|
| `applicationId` + `namespace` разделение | `app/build.gradle.kts` (`com.aistudio.ghostsuite.vkxpqz` / `com.example`) | Реализовано и подтверждено | Сборка и тесты проходят (`BUILD VERIFIED`) |
| `enableEdgeToEdge()` + Recents Guard (`setRecentsScreenshotEnabled(false)`) | `MainActivity.kt:181-185, 214` | Реализовано частично | На основном экране edge-to-edge есть, но `GhostSettingsDialog.kt` не применяет `statusBarsPadding()`/`navigationBarsPadding()`, а `GhostCameraScreen.kt:1603` наезжает на верхний HUD |
| Блокировка скриншотов выкл по умолчанию + Android 15 `ScreenRecordingCallback` | `ZverPrefs.kt:102`, `MainActivity.kt:66-74, 98-124` | Реализовано и подтверждено | Проверено `ZverSecurityFeaturesTest`; `FLAG_SECURE` принудительно включается внутри `GhostVaultDialog` |
| Маскировка иконки (3 `<activity-alias>`: Default / Calculator / System Tools) | `AndroidManifest.xml:52-94`, `CamouflageManager.kt:19-21` | **Реализовано, но имеет дефект** | **P0**: `CamouflageManager` строит `ComponentName` как `"${context.packageName}.$aliasSuffix"` (`com.aistudio.ghostsuite.vkxpqz.MainActivity*`) вместо `com.example.MainActivity*` из `merged_manifest` -> краш |
| Рабочий экран-прикрытие Калькулятор (`PIN + '='` / `1337 + '='`) | `DecoyCalculatorScreen.kt`, `MainActivity.kt:241-265` | Реализовано частично | Сама арифметика и PIN-вход работают в коде, но зависит от фикса `CamouflageManager`; поведение при `onStop`/разблокировке на устройстве требует проверки |
| GitHub Actions CI + Nightly APK Release | `.github/workflows/build.yml` | **Реализовано, но имеет дефект** | Привязан `tag_name: nightly`, при обычном пуше в `main` создаётся только Workflow Artifact (`app-debug`), а не версионированный Release с `.apk` |

### 2.2. Камера, сенсоры и вычислительная оптика

| Функция / Заявление из DOC-007 | Фактическая реализация в коде | Классификация по состоянию | Комментарий аудита |
|---|---|---|---|
| CameraX превью + фото + анализ + видео | `GhostCameraScreen.kt:785-968`, `CaptureManager.kt` | **Реализовано, но имеет дефект** | **P0**: одновременно биндит 4 UseCase (`Preview + ImageCapture + ImageAnalysis + VideoCapture`); фолбэк молча отключает `ImageAnalysis` и `VideoCapture`. На Redmi 13C зафиксированы зависания/ошибки захвата |
| Режимы `PHOTO / VIDEO / NIGHT / VISOR / SENTINEL / DOCUMENTS / STEALTH / VOICE / MACRO` | `GhostCameraScreen.kt` | Реализовано частично | UI-карусель и состояния есть; `MACRO` просто ставит `zoomRatio = 2.0f`; реальная матрица `50MP` (`GhostSettingsDialog.kt:580`) не используется (CameraX берет стандартный 12.5Мп биннинг — требует `CameraCharacteristics` проверки) |
| Запись видео в MP4 + Mute Audio + `Mp4Sanitizer` | `GhostCameraScreen.kt:561-643`, `Mp4Sanitizer.kt` | Реализовано частично | `ZverSanitizerUnitTest` подтверждает стриппинг `udta/meta` и сдвиг `stco` на синтетическом MP4; **не проверено на физическом устройстве** с реальными MP4 (вкл. 64-битный `co64` и `VideoView` воспроизведение после шифрования) |
| Сенсоры `AntiBlurSensorEngine` (гироскоп, уровень документа `<3°`, Focus Peaking, False Color, Snatch Lock, Flip-to-Disguise, Proximity) | `AntiBlurSensorEngine.kt` | Не проверено на физическом устройстве | Математика `computeMotionAndLuma` проверена в тесте; реальная калибровка порогов (`28.0 m/s²`, `0.075 rad/s`), jello и потребление батареи на Helio G85 не измерены (`UNVERIFIED`) |
| Паспортное железо: SoC `MT6768` vs `MT6769Z` + `CameraCharacteristics` | `README.md`, `AntiBlurSensorEngine.kt:16` | **Реализовано, но имеет дефект** | В документации/комментариях ошибочно указано `MT6768` вместо реального Helio G85 (`MT6769Z`/`MT6769V-CZ`), отсутствует живой диагностический опрос `CameraManager.getCameraCharacteristics()` |
| Голосовое управление без сети | `VoiceControlManager.kt`, `GhostCameraScreen.kt:730-774` | Не проверено на физическом устройстве | Использует Android `SpeechRecognizer` (на ряде прошивок HyperOS/MIUI зависит от системного офлайн-пакета речи или движка Google) |

### 2.3. Криптография, сейф и форензик-санитайзинг

| Функция / Заявление из DOC-007 | Фактическая реализация в коде | Классификация по состоянию | Комментарий аудита |
|---|---|---|---|
| 3-контурный сейф (`MAIN` / `DECOY` / `DURESS`) + PBKDF2 600k + AES-256-GCM | `GhostCryptoVault.kt` | **Реализовано, но имеет дефект** | **P0**: тест PIN-разблокировки проходит (`ZverSecurityFeaturesTest`), но `getActiveVaultKey()` при `activeMainKey == null` (до ввода PIN или после `onStop -> lockSession()`) молча переключается на `AndroidKeyStore` ключ `Ghost_Vault_MAIN`, вызывая `AEADBadTagException` при чтении файлов разблокированным PIN-ключом |
| RAM-Only контур (0 байт на диск, кап 96 МБ) | `GhostCryptoVault.kt:356-406`, `GhostVaultDialog.kt` | Реализовано и подтверждено | Проверено в `ZverSecurityFeaturesTest`; на устройстве очищается по `onDestroy` / Шредеру |
| Монолитный контейнер `MonoVaultEngine` 1 ГБ (`storage_block.bin`, AAD, стохастический паддинг, HKDF, ECDSA) | `MonoVaultEngine.kt` | Реализовано частично | Алгоритмы `buildBlockAAD` и `hkdfDeriveKey` проверены unit-тестами (`VaultIntegrityUnitTest`), НО: 1) в `GhostVaultDialog` монолит НЕ читается и НЕ используется для галереи (туда только дописываются копии фото, видео не пишется); 2) используется отдельный `AndroidKeyStore` ключ вместо PIN-ключа; 3) `ensureContainerInitialized` с `raf.setLength(1GB)` на eMMC 5.1 и отсутствие в UI просмотра монолита делают его "теневым дублёром" |
| EXIF/XMP/COM стриппинг, перекодирование WebP/JPEG, Anti-PRNU, CLAHE, Аудит | `ExifStripper.kt`, `ZverSanitizerUnitTest.kt` | Реализовано и подтверждено | Стриппинг и аудит подтверждены unit-тестами; оптическая стойкость Anti-PRNU к лабораторному PRNU-анализу — `UNVERIFIED` |
| ProofMode-lite (`.proof.json`, SHA-256 + ECDSA KeyStore) | `GhostCryptoVault.kt:728-751`, `GhostVaultDialog.kt:924-939` | Не проверено на физическом устройстве | Генерация JSON и `FileProvider` реализованы, на устройстве и внешнем верификаторе не тестировалось |
| PanicKit приёмник (`GuardianProject` + `MessageDigest.isEqual`) | `PanicTriggerReceiver.kt` | Реализовано и подтверждено (логика) | Constant-time сравнение протестировано; реальный триггер от внешнего Ripple на Android 15 — не проверен на устройстве |
| Маскировка / редактор «МАСКА» (`PrivacyEditorDialog`) | `PrivacyEditorDialog.kt`, `OfflinePrivacyEngine.kt` | Реализовано частично | Мозаика/пикселизация и OSINT-линтер (Luhn, телефон, координаты, email, пароли) подтверждены тестами; качество ML Kit на реальных документах требует проверки |
| AntiForensicsGuard (`RootBeer` + су-бинарники + debugger) | `AntiForensicsGuard.kt`, `GhostCameraScreen.kt:208` | Реализовано частично | Вычисляется `securityStatus`, но в основном используется как индикатор HUD/диагностики |

### 2.4. Био-Визор (`CyberVisorEngine`), Кибер-Дозор (`SentinelEngine`) и 6-NN Офлайн

| Функция / Заявление из DOC-007 | Фактическая реализация в коде | Классификация по состоянию | Комментарий аудита |
|---|---|---|---|
| 11 режимов Био-Визора (`BIO_RPPG`, `SKIN_CHROMA`, `EULER_AMP`, `VEIN_HEMO`, `DERMA_UV_SIM`, `THERMAL_LUT`, `DEHAZE_LIVE`, `WIREFRAME_POSE`, `VEG_CHLOROPHYLL`, `NIGHT_GAMMA`, `IR_BEACON`) | `CyberVisorEngine.kt`, `YcbcrSkinAnalyzer.kt` | **Не проверено на физическом устройстве** (и требует честной маркировки) | Математика rPPG/HRV/AE-jump/Euler/YCbCr подтверждена на синтетике в `ZverSecurityFeaturesTest`. Однако реальный FPS, задержка `2-4ms` и точность при реальном освещении на Redmi 13C **не измерены (`UNVERIFIED`)**. Более того, панель `CyberVisorControlCard` не сворачивается и загораживает обзор |
| Apex Sentinel v5.0 («Кибер-Дозор»: отсечка соседа 2/4/6с, стук, авто-CLAHE, 2-Stage кроп) | `SentinelEngine.kt`, `GhostCameraScreen.kt:839-883` | **Не проверено на физическом устройстве** | Логика конечного автомата и стука подтверждена в `ZverSecurityFeaturesTest`. Зависит от стабильности `ImageAnalysis`, который отключается при фолбэке CameraX |
| Локальный симбиоз 6-NN ML Kit (`Face`, `Object`, `Label`, `Pose`, `SelfieSeg`, `Text`) | `LocalSymbiosisCvEngine.kt` | Реализовано частично | Код запускает все 6 моделей параллельно на `Bitmap`; реальная нагрузка RAM/CPU на Redmi 13C (4 ГБ ОЗУ) не замерена (`UNVERIFIED`), а зависимость ML Kit тянет `transport-backend-cct` |

### 2.5. Вика AI (BYOK Gemini), Изоляция процессов и Приватность

| Функция / Заявление из DOC-007 | Фактическая реализация в коде | Классификация по состоянию | Комментарий аудита |
|---|---|---|---|
| BYOK Gemini (`ByokManager`, `GeminiByokClient`, `VikaAiSheet`) | `ByokManager.kt`, `GeminiByokClient.kt`, `VikaAiSheet.kt`, `GhostSettingsDialog.kt` | **Реализовано, но имеет дефект** | **P0**: 1) рассинхрон состояния выбранной модели между `GhostSettingsDialog` и `VikaAiSheet`; 2) отмена запроса моделей при закрытии диалога; 3) лимит 3 страниц без диагностики HTTP-журнала; 4) кнопка «Разбери статистику» заблокирована без ключа; 5) нет предпросмотра захваченного кадра до отправки |
| Процессная изоляция `:core` vs `:assist` (`GhostAssistService`) | `AndroidManifest.xml:117-120`, `GhostAssistService.kt` | **Присутствует только интерфейс** | `GhostAssistService` — пустой сервис; `GeminiByokClient` работает в **основном процессе UI**, а не в `:assist`. Более того, отдельный `android:process=":assist"` в Android имеет **тот же Linux UID**, общие `filesDir`/`SharedPreferences`/`KeyStore` и не даёт security-изоляции |
| «0 сетевого кода / 0 фоновой телеметрии / 0 Firebase» | `app/build.gradle.kts`, `merged_manifest` | **Реализовано, но имеет дефект** (несоответствие паспорта реальности APK) | В APK фактически вшиты `firebase-ai`, `firebase-appcheck-*`, `room-*`, `retrofit`/`moshi`, `WorkManager`, `Startup`, `Play Services` и `datatransport:transport-backend-cct:2.3.3` (через ML Kit) + `INTERNET` разрешение. `README.md` устарел |
| Интеграция Termux / Termux:API / Shizuku / MacroDroid (`zver://` + Broadcasts) | `MainActivity.kt:76-85, 145-162, 190-201`, `GhostSettingsDialog.kt:670-754` | Реализовано частично | Интенты и deep-link `zver://` работают в коде; `AppBadge` в настройках переносит `MacroDroid` по буквам (UI баг); реальный сценарий Termux/Shizuku на устройстве не проверен |
| Анализатор видеоключевых кадров (до 2 мин / 5 кадров) | `VideoKeyframeExtractor.kt` | Не проверено на физическом устройстве | Логика есть, лимиты `120с / 5 кадров` покрыты тестом, реальный декод с `MediaMetadataRetriever` на Redmi 13C не проверен |
