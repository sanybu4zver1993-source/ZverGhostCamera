# WORK_LOG.md — Журнал аудита, проверок и изменений

## 2026-11-17 — Сессия аудита и Stage 0 (Unified Baseline)

### 1. Инвентаризация и чтение исходников
- Прочитаны конфигурационные файлы: `settings.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `.env.example`, `.github/workflows/build.yml`, `README.md`, `SECURITY.md`, `app/src/main/AndroidManifest.xml`, `app/proguard-rules.pro`, `app/src/main/res/values/strings.xml`.
- Прочитаны все ключевые модули Kotlin (`MainActivity.kt`, `GhostCryptoVault.kt`, `MonoVaultEngine.kt`, `CamouflageManager.kt`, `SecureClipboard.kt`, `ZverPrefs.kt`, `SentinelEngine.kt`, `AntiForensicsGuard.kt`, `VaultAnalytics.kt`, `PanicTriggerReceiver.kt`, `ByokManager.kt`, `GeminiByokClient.kt`, `GhostAssistService.kt`, `CaptureManager.kt`, `AntiBlurSensorEngine.kt`, `CyberVisorEngine.kt`, `LocalSymbiosisCvEngine.kt`, `DecoyCalculatorScreen.kt`, `CameraHudOverlays.kt`, `GhostSettingsDialog.kt`, `VikaAiSheet.kt`, `GhostVaultDialog.kt`, `GhostCameraScreen.kt`) и все 6 файлов тестов.

### 2. Базовые команды сборки и диагностики (Baseline)
- **Команда:** `gradle :app:processDebugManifest :app:testDebugUnitTest`
- **Результат:** `BUILD SUCCESSFUL in 1m 8s`
  - Unit + Robolectric тесты: **25/25 PASS** (`0 failures, 0 errors, 0 skipped`):
    - `ExampleUnitTest`: 1
    - `ExampleRobolectricTest`: 1
    - `GhostCryptoVaultTest`: 3
    - `VaultIntegrityUnitTest`: 4
    - `ZverSanitizerUnitTest`: 3
    - `ZverSecurityFeaturesTest`: 13
- **Анализ склеенного манифеста (`app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`, 414 строк):**
  - Подтверждён `package="com.aistudio.ghostsuite.vkxpqz"` (строка 3), тогда как Activity и `<activity-alias>` резолвятся как `com.example.MainActivity`, `com.example.MainActivitySystem` (`enabled="false"`), `com.example.MainActivityCalculator` (`enabled="false"`), `com.example.MainActivityDefault` (`enabled="true"`) (строки 58–101). Доказана точная причина краша `CamouflageManager.kt:19-21`.
  - Подтверждено присутствие `INTERNET`, `ACCESS_NETWORK_STATE`, а также транзитивных разрешений `READ_GSERVICES`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`.
  - Выявлена автоинициализация `FirebaseInitProvider` + `FirebaseAIRegistrar` + `FirebaseAppCheck*`, `MlKitInitProvider`, `androidx.startup.InitializationProvider` (`WorkManager`, `EmojiCompat`, `ProcessLifecycle`, `ProfileInstaller`), `Room MultiInstanceInvalidationService` и Google DataTransport Clearcut (`TransportBackendDiscovery` / `CctBackendFactory` / `JobInfoSchedulerService` / `AlarmManagerSchedulerBroadcastReceiver`).
- **Анализ дерева зависимостей (`gradle :app:dependencies --configuration debugRuntimeClasspath > /tmp/zver_deps.txt`):**
  - Подтверждено: `firebase-ai`, `firebase-appcheck-*`, `ktor-client-okhttp:3.0.3`, `play-services-recaptchabase:16.1.0`, `room-*`, `retrofit:2.12.0`, `converter-moshi`, `logging-interceptor`, `moshi-kotlin` включены в сборку при **0 вызовах** в `app/src/main/java/`.
  - Подтверждено: все 6 библиотек ML Kit подтягивают `com.google.android.datatransport:transport-backend-cct:2.3.3`.
- **Grep по `app/src/main/java/`:**
  - Поиск `firebase|appcheck|retrofit|moshi|androidx\.room|CAMERA_50MP|MT6769` вернул 0 совпадений.

### 3. Оформление комплекта Stage 0 документации
- Созданы документы:
  - `/DOCUMENT_INDEX.md` (учтён `DOC-007` — Технический паспорт ZVER Camera, классифицированы все функции по 6 статусам)
  - `/MASTER_SPEC.md` (исправлен SoC `MT6768` → `MT6769Z`, зафиксированы доказательства 4 P0-багов, аудит приватности и маркировка `UNVERIFIED`)
  - `/REQUIREMENTS_TRACEABILITY.md` (`REQ-001`..`REQ-016`)
  - `/ARCHITECTURE_DECISIONS.md` (`ADR-001`..`ADR-006`)
  - `/IMPLEMENTATION_PLAN.md` (план `P0 → P4` с жёсткими ограничениями безопасности)
  - `/WORK_LOG.md` (текущий журнал)
  - `/IMPLEMENTATION_STATUS.md` (матрица готовности и статусов)

### 4. Реализация и верификация Этапа P0 (2026-11-17)
- **P0-A (`CamouflageManager.kt`):**
  - Исправлен `CamouflageMode.getComponentName(context)`: класс алиаса берётся от `MainActivity::class.java.package?.name` (`com.example.<Alias>`) при сохранении `context.packageName` (`com.aistudio.ghostsuite.vkxpqz`) без изменения `namespace`/`applicationId`.
  - Добавлен `verifyManifestAliases(context)`.
  - Покрыто Robolectric-тестом `P0DiagnosticsAndFixesTest.testCamouflageManifestAliasesResolveAndToggleWithoutCrash`. Поведение лаунчера HyperOS 2.0 на устройстве остаётся `UNVERIFIED`.
- **P0-B (`GhostCryptoVault.kt`, `GhostVaultDialog.kt`):**
  - Исправлено глушение ошибки закрытия потока в `encryptFileToVault` (`cipherOut.use { ... }` с крипто-шредом временного/целевого файла при сбое финализации GCM-тега).
  - Штатное `decryptToRam` строго выбрасывает `AEADBadTagException` (запрещён тихий fallback на другой ключ) с диагностической подсказкой активного ключа (`getActiveVaultKeySourceLabel`).
  - Добавлены read-only инспектор заголовков (`inspectGcfHeader`), read-only диагностика источника ключа (`diagnoseDecryptKeySource`, `diagnoseVaultDirectory`), read-only резервное копирование в `noBackupFilesDir` (`backupVaultDirectoryReadOnly`) и копирующее восстановление строго в отдельный файл (`recoverGcfFileToCopy`, бросает `IllegalArgumentException` при попытке перезаписать оригинал).
  - В `GhostVaultDialog.kt` добавлены кнопки read-only диагностики файла/директории (`🩺 КЛЮЧИ`) и read-only бэкапа (`📦 БЭКАП (КОПИЯ)`). Оригинальные 30 файлов `.gcf` на устройстве НЕ модифицируются.
  - Покрыто тестом `P0DiagnosticsAndFixesTest.testVaultReadOnlyDiagnosticsStrictNoFallbackBackupAndCopyRecovery` (проверены неизменность SHA-256 оригинала, отказ штатного `decryptToRam` на чужом ключе, определение ключа, бэкап и восстановление в копии).
- **P0-C (`ByokManager.kt`, `GeminiByokClient.kt`, `VikaAiSheet.kt`, `GhostSettingsDialog.kt`):**
  - Добавлены общие реактивные `StateFlow` в `ByokManager` (`hasKeyFlow`, `selectedModelFlow`, `discoveredModelsFlow`, `customPromptFlow`, `isFetchingModelsFlow`, `modelFetchStatusFlow`, `chatHistoryFlow`, `sessionStatsFlow`, `httpLogsFlow`).
  - Загрузка списка моделей переведена в `SupervisorJob() + Dispatchers.IO` (`refreshModelsInBackground`), пагинация расширена до 15 страниц с защитой от зацикливания токенов (`seenTokens`), убрана тихая автоподмена выбранной модели.
  - Добавлен очищенный от API-ключей кольцевой журнал HTTP-запросов (`sanitizeForLog` маскирует `AIza...` и `key=`) с выводом и копированием в `VikaAiSheet` и `GhostSettingsDialog`.
  - В `VikaAiSheet` добавлены предпросмотр захваченного кадра (миниатюра + кнопка сброса) и офлайн-кнопка анализа статистики (`VaultAnalytics.buildTelemetrySummaryForAi`) при отсутствии ключа/сети.
  - Покрыто тестом `P0DiagnosticsAndFixesTest.testByokLogSanitizationAndNoSilentModelSubstitution`. Живые HTTP-ответы Gemini API на телефоне — `UNVERIFIED`.
- **P0-D (`CameraDiagnostics.kt`, `GhostCameraScreen.kt`, `AntiBlurSensorEngine.kt`, `README.md`):**
  - Создан `CameraDiagnostics` для живого опроса `Build.SOC_MODEL`/`Build.HARDWARE` и `CameraManager.getCameraCharacteristics()`.
  - Разделена привязка CameraX в `GhostCameraScreen`: `VIDEO` -> `Preview + VideoCapture (HD)` с отложенным авто-стартом через `pendingAutoStartVideo`; фото/оптика -> `Preview + ImageCapture + ImageAnalysis`; выводится `boundUseCasesSummary`.
  - В `GhostSettingsDialog` добавлен блок `5. ДИАГНОСТИКА CAMERA2 / HAL (ФАКТИЧЕСКАЯ)` с копированием отчёта.
  - Исправлена маркировка SoC `MT6768` → `MT6769Z` в `AntiBlurSensorEngine.kt` и `README.md`.
- **P0-E (`GhostSettingsDialog.kt`, `GhostCameraScreen.kt`):**
  - В `GhostSettingsDialog` добавлены `.statusBarsPadding().navigationBarsPadding().imePadding()` и `FlowRow` (`softWrap = false`) для 4 `AppBadge` (устранён перенос `MacroDroid` по буквам).
  - В `GhostCameraScreen` баннер уведомлений перенесён внутрь верхней HUD-колонки (устранён наезд с `padding(top = 86.dp)`), а `CyberVisorControlCard` получила кнопку сворачивания `▲ РАЗВЕРНУТЬ / ▼ СВЕРНУТЬ`.
- **Итоговые команды проверки после правок P0:**
  - `gradle :app:testDebugUnitTest --rerun-tasks --console=plain` -> **BUILD SUCCESSFUL** (33 executed, **28/28 PASS**: `ExampleUnitTest`: 1, `ExampleRobolectricTest`: 1, `GhostCryptoVaultTest`: 3, `P0DiagnosticsAndFixesTest`: 3, `VaultIntegrityUnitTest`: 4, `ZverSanitizerUnitTest`: 3, `ZverSecurityFeaturesTest`: 13).
  - `compile_applet` -> **Build succeeded - the applet is compiled**.

---

## 2026-11-18 — Реализация и верификация Этапов P1–P4 и исправление CI/API 35

### 1. Этап P1 (Pro Camera2 HAL, Photo IQ, Zoom Intelligence, HUD, Vault Photo Editor)
- Добавлены `CameraCapabilityProfiler.kt`, `PhotoIqEngine.kt`, `ZoomIntelligence.kt`, расширен `CameraHudOverlays.kt` (Live-гистограмма яркости + Zebra пересветов), добавлен неразрушающий `VaultPhotoEditorDialog.kt` (выгружает отредактированную копию, не перезаписывая оригинал `.gcf`).
- Интегрировано в `GhostCameraScreen.kt`, `GhostSettingsDialog.kt` (секция `1B`) и `GhostVaultDialog.kt`.
- Покрыто тестом `P1ProCameraAndIqTest.kt` (**5/5 PASS**).

### 2. Этап P2 (Multi-Frame Stacking, Smart Compression, Приватность и Монолит UI)
- Создан `MultiFrameStacker.kt` (`NIGHT_DENOISE`, `HDR_FUSION`, `DETAIL_RECOVERY`) с поддержкой стекинга в `CaptureManager.kt`.
- Реализовано интеллектуальное сжатие и санитация (`ExifStripper.kt`, `ZverPrefs.kt`, сдвиг `co64` в `Mp4Sanitizer.kt`).
- Закомментированы неиспользуемые зависимости в `app/build.gradle.kts` (Firebase AI/AppCheck, Room, Retrofit, Moshi), добавлены `tools:node="remove"` для Clearcut DataTransport в `AndroidManifest.xml`, выведена вкладка `МОНОЛИТ` (`MonoVaultEngine`) в `GhostVaultDialog.kt`, выровнен честный статус `GhostAssistService.kt`.
- Покрыто тестом `P2BurstAndPrivacyTest.kt` (**4/4 PASS**).

### 3. Этап P3 (PCB Lab, Audio Watcher, Доп. режимы CV и CI/API 35 Releases)
- Созданы `PcbInspectorEngine.kt`, `PcbLabDialog.kt` (SMD-декодер, EIA-96, 4-band резисторы, поиск мостиков припоя и зон перегрева/прогара) и `AudioWatcherEngine.kt` (PCM-16 мониторинг тишины/громких всплесков/импульсов).
- Расширен `CyberVisorEngine.kt` до 13 режимов (`EDGE_TRACE` Sobel, `OCR_CONTRAST`), подключено в `GhostCameraScreen.kt` и `GhostVaultDialog.kt`.
- По скриншотам падений GitHub Actions выровнен SDK проекта под стабильный runner-стек: `compileSdk = 35`, `targetSdk = 35` (`app/build.gradle.kts`), `coreKtx = "1.15.0"` (`gradle/libs.versions.toml`), а `.github/workflows/build.yml` переведён на JDK 21, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v3`, автотегирование и `softprops/action-gh-release@v2` с прямой выдачей `ZverCamera-<tag>.apk`.
- Покрыто тестом `P3PcbAndAudioTest.kt` (**4/4 PASS**).

### 4. Этап P4 (ProofMode верификация, Batch Chain-of-Custody и честность документации)
- В `GhostCryptoVault.kt` добавлены `ProofVerificationResult`, `verifyDetachedProofJson` (SHA-256 + ECDSA с защитой от битого JSON/Base64) и `generateVaultChainManifestJson` (детерминированная хронологическая сортировка, цепочка `chain_hash = SHA256(prevChainHash + fileSha256 + fileName)`, `chain_head_sha256`, подпись `manifest_ecdsa_sig_b64` без модификации исходных `.gcf`).
- В `GhostVaultDialog.kt` добавлены кнопки `⛓️ ЦЕПЬ` (экспорт `chain_manifest.json` через `FileProvider`) и `ПРОВЕРИТЬ` внутри отчёта аудита доказательств.
- В `GhostSettingsDialog.kt` (`MODES_GUIDE`) убрано завышенное заявление про 50MP (заменено на честное описание биннинга HAL + Pro HAL + Photo IQ + мульти-кадровый стек), добавлена карточка `PCB ЛАБ & АКУСТИК-СТОРОЖ` и обновлена справка Bio-Visor `v2.2 (13-в-1)`.
- Покрыто Robolectric-тестом `P4ChainOfCustodyTest.kt` (**2/2 PASS**).

### 5. Финальная сквозная верификация (Stage 0 + P0–P4)
- **Команда:** `gradle :app:testDebugUnitTest --rerun-tasks --console=plain`
- **Результат:** `BUILD SUCCESSFUL in 1m 57s` (31 task executed), **43/43 PASS** (`0 failures, 0 errors, 0 skipped`):
  - `ExampleRobolectricTest`: 1/1 PASS
  - `ExampleUnitTest`: 1/1 PASS
  - `GhostCryptoVaultTest`: 3/3 PASS
  - `P0DiagnosticsAndFixesTest`: 3/3 PASS
  - `P1ProCameraAndIqTest`: 5/5 PASS
  - `P2BurstAndPrivacyTest`: 4/4 PASS
  - `P3PcbAndAudioTest`: 4/4 PASS
  - `P4ChainOfCustodyTest`: 2/2 PASS
  - `VaultIntegrityUnitTest`: 4/4 PASS
  - `ZverSanitizerUnitTest`: 3/3 PASS
  - `ZverSecurityFeaturesTest`: 13/13 PASS

---

## 2026-11-18 — Поэтапные исправления по логам Redmi 13C и видеотесту: Часть 1

### 1. Реализованные изменения (Часть 1)
- **`DecoyCalculatorScreen.kt`:** Вынесены `GhostCryptoVault.isConfigured(context)` и `GhostCryptoVault.unlockWithPinOutcome(...)` (600 000 итераций PBKDF2) из Main-потока в `withContext(Dispatchers.Default)` внутри `pressEquals()` с защитой `isUnlocking` и индикацией `"Проверка..."` (устранение ANR при вводе ПИН `939393`).
- **`MainActivity.kt`:** Вынесен вызов `shredEphemeralShareFiles` / `shredOrphanedVideoTemps` в `onResume()` на `lifecycleScope.launch(Dispatchers.IO)`.
- **`VikaAiTabs.kt` (новый файл) и `VikaAiSheet.kt`:** Вынесены UI-вкладки (`VikaTasksTabContent`, `VikaAnalyticsTabContent`, `VikaKeysModelsTabContent` и вспомогательные компоненты), чтобы метод `VikaAiSheet` не превышал порог ART compiler (`24552` инструкций); операции `ByokManager.syncFromPrefs`, `VaultAnalytics.captureSnapshot`, `ByokManager.isStrongBoxSupported`, `decryptKey`/`deleteKey`, `VideoKeyframeExtractor.extractForAi` и `LocalSymbiosisCvEngine` переведены в `Dispatchers.IO` / `Dispatchers.Default`.
- **`GhostCameraScreen.kt`:**
  - Начальные `GhostCryptoVault.totalVaultItemsCount` и `AntiForensicsGuard.assessDeviceSecurity` (RootBeer/su-проверки) переведены в `LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ... } }`; удалён дубликат `LaunchedEffect(zoomRatio, proProfile)`.
  - Режим `PreviewView` переключён на `PreviewView.ImplementationMode.PERFORMANCE` (аппаратный `SurfaceView` без лишнего копирования на MT6769).
  - Перестроен конвейер `VideoCapture`: `LaunchedEffect(lensFacing, isVideoCameraMode, previewView)`, корректная остановка активной записи, `activeImageAnalysis?.clearAnalyzer()` и `unbindAll()`, привязка `preview.setSurfaceProvider(pv.surfaceProvider)` до `bindToLifecycle(...)`, `QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))`, задержка `delay(350)` перед `pendingAutoStartVideo`, трекинг записанных байт через `VideoRecordEvent.Status` (индикатор `🔴 ЗАПИСЬ: MM:SS • X.X МБ` / `🔴 ОЖИДАНИЕ КАДРОВ HAL`), а также очистка в `onDispose`.
  - Кнопка Шредера (`panic_shred_button`) убрана из нижней панели затвора и перенесена в шторку инструментов (`Tools Drawer`), а в диалог `showPanicConfirm` добавлено обязательное текстовое подтверждение `"999"`.

### 2. Верификация Части 1
- **Команда:** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL in 1m 40s**, **43/43 PASS** (`0 failures, 0 errors, 0 skipped`).
- **Сборка:** `compile_applet` -> **Build succeeded - the applet is compiled**.
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15):** поведение после правок (скорость разблокировки `939393`, фактическая запись кадров через `c2.mtk.avc.encoder`, открытие `VikaAiSheet` без фризов) — `UNVERIFIED` до проверки пользователем на устройстве.

---

## 2026-11-18 — Поэтапные исправления по логам Redmi 13C и видеотесту: Часть 2 (HUD, меню `⋮`, PRO-панель, EV, Настройки)

### 1. Реализованные изменения (Часть 2)
- **`CameraCapabilityProfiler.kt`, `PhotoIqEngine.kt`, `MultiFrameStacker.kt`:**
  - Заменены краткие/англоязычные метки (`AUTO`, `RAW`, `IQ`, `CRISP`, `NIGHT+`, `N-STACK`, `HDR-STK`, `DETAIL+`, `SUN`, `CLD`, `TUNG`, `FLUO`) на понятные русские названия (`"Авто"`, `"Обычный"`, `"Авто IQ"`, `"Текст+"`, `"Ночь+"`, `"Ночь-стек"`, `"HDR-стек"`, `"Детали+"`, `"Солнце"`, `"Облака"`, `"Лампа"`, `"Люминесц."`).
  - Добавлены общие функции синхронизации стопов экспозиции: `CameraCapabilityProfiler.formatEvStops(exposureIndex, evStep)` (`"EV 0.0"`, `"EV +0.5"`, `"EV -1.0"`) и `CameraCapabilityProfiler.evIndexForStops(targetStops, evStep, evRange)`.
- **`CameraHudOverlays.kt`:**
  - В `FocusPeakingOverlay`, `FalseColorExposureOverlay` и `ZebraExposureOverlay` добавлен параметр `showBadge: Boolean = true`, а также убраны жёсткие нижние якоря бейджей (`padding(bottom = 215.dp)` / `BottomEnd`), чтобы не наезжать на зум `1x..10x` и нижние пилюли.
  - `LiveHistogramOverlay` переведена в компактный полупрозрачный формат (`alpha = 0.56f`, высота `36.dp`, русские метки `Ярк:` / `Т:` / `С:`).
  - `ProCameraControlPanel` переделана в компактную полупрозрачную панель (`alpha = 0.56f`) со строкой статуса (`PRO • ...`, `"Гист."`, `"Зебра"`, `"Фикс AE"`), слайдером стопов EV через `formatEvStops` и 3 переключаемыми вкладками (`IQ`, `ББ`, `РУЧН`) по одной полосе за раз с сохранением всех `testTag`.
- **`GhostCameraScreen.kt`:**
  - Верхняя панель очищена от нагромождения `↓ИНСТР / ↑ВИКА`: оставлена строка `"$stabilityText • Резкость ..."` с `maxLines = 1, TextOverflow.Ellipsis`, иконки приведены к `40.dp` и добавлено компактное выпадающее меню `⋮` (`top_overflow_menu_button`) с быстрыми действиями (Настройки, PRO-панель, Гистограмма, Зебра, PCB Лаб, Камуфляж, Стелс, Вика AI).
  - Наложения переданы с `showBadge = false`, а `LiveHistogramOverlay` и компактные телеметрические чипы перенесены в потоковый `Row` внутри верхней HUD-колонки ниже баннера уведомлений (показываются только при закрытой шторке инструментов), что исключает перекрытие с верхними кнопками, плашкой записи и нижним зумом.
  - Нижняя карусель режимов сокращена до 6 основных режимов съёмки (`"Видео"`, `"Фото"`, `"Ночь"`, `"Визор"`, `"Дозор"`, `"Документы"`) без обрубка `"ДОК"` и дублей `"СТЕЛС"`/`"ГОЛОС"` (поддержка `STEALTH`/`VOICE` сохранена в шторке инструментов, меню `⋮` и справке Настроек); скорректированы границы свайпа `0..lastIndex` (по умолчанию `1` — `"Фото"`).
  - Нижняя пилюля `EV` и режим `"Ночь"` (`+0.5 EV`) синхронизированы со шкалой PRO-слайдера через `CameraCapabilityProfiler.formatEvStops` и `evIndexForStops`.
  - В шторке инструментов (`DrawerTileButton`, высота `58.dp`) заменены непонятные сокращения на полные 2-строчные русские подписи (`"Фокус-\nпикинг"`, `"Карта\nэкспозиции"`, `"Уровень\nлиста"`, `"Анти-\nтуман"`, `"Таймер\n3 сек"`, `"PRO-\nпанель"`, `"Гисто-\nграмма"`, `"Зебра\nсвета"` и т.д.).
  - `CyberVisorControlCard` в свёрнутом состоянии (`isCollapsed == true`) схлопывается в одну компактную строку (`"🔬 Био-Визор: ..."` + `"В сейф"` + `"▲ Развернуть"`).
- **`GhostSettingsDialog.kt`:**
  - В `DialogProperties` добавлено `decorFitsSystemWindows = false` для корректной передачи системных отступов Android 15 / HyperOS 2 в `.statusBarsPadding()` и `.navigationBarsPadding()`; увеличен нижний отступ (`bottom = 20.dp`, минимальная высота кнопки `"ЗАКРЫТЬ НАСТРОЙКИ"` `48.dp`).
  - Дедуплицирована вкладка `MODES_GUIDE` (удалена псевдо-карточка `"МАКРО (2x)"` в пользу `"☀️ ФОТО (+ PRO И МАКРО-ЗУМ)"`, уточнены карточки `"ГОЛОСОВОЕ УПРАВЛЕНИЕ"` и `"СТЕЛС-ЭКРАН"`).
- **`P1ProCameraAndIqTest.kt`:**
  - Добавлен тест `testEvStopsFormattingAndSteppingSynced` для проверки синхронности форматирования и шага стопов EV.

### 2. Верификация Части 2
- **Команда:** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL in 1m 39s**, **44/44 PASS** (`0 failures, 0 errors, 0 skipped`, включая `P1ProCameraAndIqTest`: 6/6 PASS).
- **Сборка:** `compile_applet` -> **Build succeeded - the applet is compiled**.
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15 / HyperOS 2):** визуальная чистота HUD, меню `⋮`, полупрозрачность PRO-панели, совпадение стопов `EV` между пилюлей и PRO-слайдером, доступность кнопки `"ЗАКРЫТЬ НАСТРОЙКИ"` над 3-кнопочной панелью навигации и однострочное сворачивание Bio-Visor — `UNVERIFIED` до проверки на устройстве.

