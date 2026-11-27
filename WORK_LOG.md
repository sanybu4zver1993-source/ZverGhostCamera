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

---

## 2026-11-18 — Поэтапные исправления по логам Redmi 13C и видеотесту: Часть 3 (Био-Визор: запись с фильтром, фото .gcf, Хлорофилл, Скелет, Тепло-Кожа, Дорожки ПП)

### 1. Реализованные изменения (Часть 3)
- **`VisorVideoRecorder.kt` (новый файл):**
  - Создан аппаратный H.264/AAC рекордер (`MediaCodec` + `MediaMuxer`) с конвертером `VisorYuvPacker` (`NV12` `YUV420SemiPlanar`, `I420` `YUV420Planar` и fallback `COLOR_FormatYUV420Flexible` через `writeArgbToCodecImage` с учётом произвольных `rowStride`/`pixelStride`), выравниванием сторон до кратности 16 (макс. 640px), защищённой инициализацией опциональной AAC-дорожки с graceful degradation в видео-без-звука и неблокирующей однослотовой очередью `enqueueFrame`.
- **`CyberVisorEngine.kt`:**
  - В `VisorTelemetry` добавлены поля `vegLivePercent`, `vegSyntheticPercent`, `edgeTraceDensityPercent`.
  - Добавлен метод `applyVisorEffectToBitmap(src, mode, poseOverlay)` для детерминированного наложения активного фильтра Био-Визора на полноразмерные фото и кадры мульти-стека.
  - **`VEG_CHLOROPHYLL` (`Хлорофилл`):** `classifyChlorophyllPixel` + `renderChlorophyllCoherencePass` (нормализованный ExG/ExGR + поглощение синего канала + фильтр неоновых красителей + 5x5 проверка органической микротекстуры листа) разделяют живую листву (изумруд) и гладкий зелёный пластик / бирюзу / синтетику (пурпурно-янтарная отметка).
  - **`WIREFRAME_POSE` (`Скелет`):** добавлен сглаживатель `PoseTemporalSmoother` (гистерезис захвата `>= 0.50f` и удержания `>= 0.32f`, 3-кадровый coasting при кратковременной окклюзии, подавление выбросов `dist > 0.20f` и адаптивный по скорости EMA `0.10f..0.56f` против дрожи кистей вблизи).
  - **`SKIN_CHROMA` (`Тепло-Кожа`):** добавлен RGB-фильтр тёплого тона кожи `isWarmHumanSkinRgbGuard` (отсев серых/холодных/зелёных стен), оценка глобального сдвига яркости `globalLumaShift` и 4-соседская проверка движения против ложных срабатываний при дрожи рук; усилен тепловой контраст статики (янтарь/оранжевый) и движения (циан-мята).
  - **`EDGE_TRACE` (`Дорожки ПП`):** добавлен честный 3x3 Sobel по взвешенной зелёной яркости (`renderSobelPcbTracePass`), выделяющий яркие контактные площадки/выводы золотым и тонкие медные дорожки циан-изумрудом на приглушённой подложке.
- **`CaptureManager.kt`:**
  - В `takeSecurePhoto` и `captureMultiFrameStack` добавлены `visorMode` и `visorEngine`: темпоральные режимы (`EULER_AMP`, `BIO_RPPG`) забирают обработанный кадр из `snapshotLatestBitmapCopy()`, пространственные фильтры накладываются через `applyVisorEffectToBitmap` на выпрямленный кадр (с ограничением `2_400_000L` пикселей) до `ExifStripper`, шифрования в `.gcf` и зеркалирования в `MonoVaultEngine` (`iqTag = "ВИЗОР:${visorMode.badgeRu}"`).
  - Добавлен `saveExternalBitmapToVault(...)` для прогона снимков кнопки `"В сейф"` через `ExifStripper`, Сейф и `MonoVaultEngine`.
- **`GhostCameraScreen.kt`:**
  - Убран принудительный сброс `visorMode = VisorMode.NORMAL` при выборе режима `"Видео"`; добавлено удержание конвейера `Preview + VideoCapture + ImageAnalysis` при активном Био-Визоре в видеорежиме (с безопасным fallback) и отправка кадров (включая нарисованные кости `WIREFRAME_POSE`) в `VisorVideoRecorder`.
  - Финализация видео Био-Визора прогоняется через `Mp4Sanitizer.sanitizeMp4File`, `GhostCryptoVault.encryptFileToVault` / `addRamShot` и шредер временных файлов; кнопка `video_record_button` отображает стоп-иконку при записи; `CyberVisorControlCard` выводит живую телеметрию по режимам `Хлорофилл`, `Дорожки ПП`, `Тепло-Кожа` и `Скелет`.
- **`P3VisorRefinementsTest.kt` (новый файл):**
  - Добавлено 5 JVM/Robolectric-тестов (сохранение эффекта на `Bitmap`, отличие живой листвы от пластика и бирюзы, подавление дрожи/выбросов `PoseTemporalSmoother`, 3x3 Sobel на дорожках и площадках ПП, `isWarmHumanSkinRgbGuard` + `VisorYuvPacker`).

### 2. Верификация Части 3
- **Команда:** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL**, **49/49 PASS** (`0 failures, 0 errors, 0 skipped`, включая `P3VisorRefinementsTest`: 5/5 PASS).
- **Сборка:** `compile_applet` -> **Build succeeded - the applet is compiled**.
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15):** аппаратное кодирование H.264/AAC в `VisorVideoRecorder` (`c2.mtk.avc.encoder`), реальная калибровка живой листвы против зелёного пластика, стабильность ML Kit Pose на близкой дистанции и контраст Sobel-дорожек на плате — `UNVERIFIED` до проверки пользователем на устройстве.

---

## 2026-11-19 — Часть 4 (Сейф без лагов, Шредер, FLAG_SECURE, Редактор блюра + Drag, Офлайн-войсченджер) + Срочные фиксы по живому logcat Redmi 13C

### 1. Реализованные изменения (Часть 4 + Logcat-оптимизация)
- **`OfflineVoiceChanger.kt` (новый файл):**
  - 100% офлайн-анонимайзер голоса для MP4 в Сейфе (`VoicePreset`: `ORIGINAL`, `DEEP_ANON` 0.74x, `GRAVE_BASS` 0.62x, `CYBER_ROBOT` 0.86x + кольцевая модуляция 46 Гц, `HIGH_PITCH` 1.34x).
  - Видеотрек переносится zero-copy через `MediaExtractor`/`MediaMuxer`; аудиодорожка декодируется в PCM16, обрабатывается OLA с сохранением точной длительности (0 мс рассинхрона A/V) и кодируется обратно в AAC-LC 96 кбит/с; тихие видеофайлы без аудиодорожки обрабатываются безопасно.
- **`GhostVaultDialog.kt`:**
  - Вся тяжёлая криптография и дисковый I/O (`isConfigured`, `setupVaultPins`, 600k PBKDF2 `unlockWithPinOutcome`, авто-вайп при исчерпании попыток, `refreshFiles`, удаление одиночного файла и полный шредер) вынесены с главного потока на `Dispatchers.IO`/`Dispatchers.Default` с индикаторами загрузки (`isConfigLoaded`, `isPinBusy`, `isWiping`).
  - Добавлено подтверждение удаления одиночного файла через диалог `pendingSingleShred`; все красные кнопки шредера работают асинхронно.
  - Синхронизирован `FLAG_SECURE` внутри Сейфа с настройкой `ZverPrefs.isScreenRecBlockEnabled(context)` (если пользователь отключил блок записи экрана в Настройках — Сейф также позволяет запись).
  - Добавлено `decorFitsSystemWindows = false` в `DialogProperties` для корректного отступа над 3-кнопочной навигацией Android 15.
  - Встроен офлайн-просмотр и сохранение новой изменённой копии MP4 с изменённым голосом (`"ПРИМЕНИТЬ ГОЛОС"` -> предпросмотр -> `"В СЕЙФ"` через `Mp4Sanitizer`, оригинальный зашифрованный файл не перезаписывается).
- **`OfflinePrivacyEngine.kt`, `PrivacyEditorDialog.kt`, `VaultPhotoEditorDialog.kt`, `VikaAiSheet.kt`:**
  - В `DialogProperties` всех полноэкранных диалогов добавлено `decorFitsSystemWindows = false`.
  - В `OfflinePrivacyEngine.moveRegionClamped(...)` и `PrivacyEditorDialog` добавлено плавное перетаскивание (`drag`) уже нарисованных областей блюра/пикселизации с ограничением в `[0f..1f]` (тап по боксу по-прежнему удаляет его, драг с пустого места рисует новый бокс).
- **`VisorVideoRecorder.kt`:**
  - Кодер видео переведён с медленного попиксельного YUV-пакера на `COLOR_FormatSurface` + `createInputSurface()` с 2-буферным пулом преаллоцированных `Bitmap` (`0` LOS-аллокаций на кадр) и прямым рисованием `PoseWireframe` на Canvas Surface (`lockHardwareCanvas()` с fallback на `lockCanvas(null)`).
  - Добавлен `MuxerLifecycleGuard` против повторного вызова `MediaMuxer.stop()` и `MPEG4Writer: Stop() called but track is not started or stopped`. (Публичный API `VisorYuvPacker` сохранён для обратной совместимости.)
- **Модуляризация `GhostCameraScreen.kt` + CV-диета + Lifecycle-очистка:**
  - Экран `GhostCameraScreen.kt` разбит на `CameraOverlayComponents.kt`, `CameraHudComponents.kt`, `CameraDialogsLayer.kt` и компактный корневой `GhostCameraScreen.kt`, чтобы каждый `@Composable` метод оставался значительно ниже лимита компилятора ART (`32898` инструкций, прекращён спам интерпретатора каждые 200 мс).
  - Тикающие значения телеметрии (`liveLaplacianVariance`, `gyroAngularSpeed`, `recordingDurationSeconds`, `recordingBytesWritten`, `visorDisplayState`, `peakingPoints`, `liveZebraPoints`) вынесены в `@Stable class CameraTelemetryState` и читаются только в листовых виджетах.
  - Для `ImageAnalysis` задана стратегия `ResolutionSelector` (`640x480`) + `STRATEGY_KEEP_ONLY_LATEST`, троттлинг фоновых CV-проходов до `>= 230 мс` (~4.3 Гц) с пропуском отключённых оверлеев и передачей кадров `WIREFRAME_POSE` в `VisorVideoRecorder` без `snapshotLatestBitmapCopy()`.
  - Исправлено залипание уровня `"НАКЛОН"` при переключении режима с `"Документы"` на другие режимы; добавлен `LifecycleEventObserver` (`ON_PAUSE`/`ON_STOP` останавливает активную запись, чистит анализатор и отвязывает `ProcessCameraProvider` до уничтожения `SurfaceView`, `ON_START` перепривязывает камеру).
- **`P4VaultAndPerfTest.kt` (новый файл):**
  - Добавлено 5 Robolectric-тестов (проверка `OfflineVoiceChanger.transformPcm16Samples` на сохранение точной длины без рассинхрона и изменение тембра, `moveRegionClamped`, `ZverPrefs.isScreenRecBlockEnabled`, `MuxerLifecycleGuard` против двойного/пустого stop, безопасный `stopAndFinalize()`).

### 2. Верификация Части 4
- **Команда:** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL**, **54/54 PASS** (`0 failures, 0 errors, 0 skipped`, включая `P4VaultAndPerfTest`: 5/5 PASS).
- **Сборка:** `compile_applet` -> **Build succeeded - the applet is compiled**.
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15):** плавность UI без ART-спама `32898`, реальные 24–30 FPS при записи видео Визора через `COLOR_FormatSurface`, отсутствие ошибки `BufferQueue has been abandoned` / `MPEG4Writer` и тест офлайн-войсченджера в Сейфе — `UNVERIFIED` до проверки пользователем на устройстве.


---

## 2026-11-19 — Шаг А (WebP/Photo ускорение) + Часть 5 (Малипусики UX) + Шаги Б/В (OpenGL ES 3.0 Dual-Output + GLSL Шейдеры)

### 1. Реализованные изменения
- **Шаг А — Ускорение фото-пайплайна WebP (`ExifStripper.kt`, `MultiFrameStacker.kt`):**
  - В `ExifStripper.processAndPurgeExif`: `inSampleSize` теперь учитывает `preset.maxDim`, исключая лишнее декодирование и поворот 8 МП кадра перед даунскейлом.
  - В `ExifStripper.processBitmapClean`: `ByteArrayOutputStream(65_536)` преаллоцирован против повторных расширений буфера.
  - В `ExifStripper.applyAntiPrnuCascade`: удалены два промежуточных полноразмерных `Bitmap.createScaledBitmap`; асимметричный кроп рендерится напрямую через синусоидальную 8×8 сетку `drawBitmapMesh` в итоговый `warped` битмап.
  - В `ExifStripper.stripWebpMetadataChunks`: добавлен zero-allocation fast-path, когда в контейнере отсутствуют чанки `EXIF`/`XMP `/`ICCP` и биты флагов `VP8X` (`0x2C`), а при наличии метаданных выполняется прямая запись чанков без множественных `copyOfRange` и in-place обновление RIFF-размера и флагов `VP8X`.
  - В `MultiFrameStacker.estimateSmartCompressionQuality`: ~7 000 одиночных JNI-вызовов `bitmap.getPixel(...)` заменены построчным чтением `bitmap.getPixels(twoRows, 0, w, 0, y, w, 2)` (~48 вызовов).
- **Часть 5 — UX полировка («Малипусики»):**
  - `VoiceControlManager.kt`: добавлен чистый маршрутизатор `parseCommand` с маппингом `"выключи таймер"`, `"таймер выкл"`, `"сбрось таймер"`, `"без таймера"`, `"убери таймер"`, `"отмени таймер"` → `VoiceCommand.TIMER_OFF` (до ветки `"таймер"` → `TIMER_5`) и поддержкой `"стоп"` / `"останови"` → `TOGGLE_VIDEO`.
  - `PcbLabDialog.kt` и `GhostSettingsDialog.kt`: добавлены `BackHandler { onDismiss() }` и `decorFitsSystemWindows = false` в `DialogProperties`.
  - `CameraHudComponents.kt` (`RecordingAndStatusBanner`, `AudioWatcherMeterStrip`): цветовое разделение ошибок/предупреждений (`CyberRed` / `NeonYellow` vs `CyberGreen`), `maxLines = 2` + `TextOverflow.Ellipsis`, клик для закрытия неактивного статусного баннера (`onDismissStatus`), увеличенная зона нажатия `38x32 dp` для пороговых чипов `AudioWatcher`.
  - `GhostCameraScreen.kt`: авто-очистка транзиентных не-ошибочных баннеров `lastStatusMessage` через 4 секунды и `BackHandler` для сворачивания открытой шторки инструментов или PRO-панели.
- **Шаги Б и В — Единый GPU-конвейер OpenGL ES 3.0 Dual-Output + GLSL-шейдеры (`GlVisorDualRenderer.kt`, `VisorVideoRecorder.kt`, `CyberVisorEngine.kt`, `CameraOverlayComponents.kt`, `GhostCameraScreen.kt`):**
  - Создан `GlVisorDualRenderer.kt`: выделенный `HandlerThread("ZverGlVisor")`, инициализация `EGL14` (`ES 3.0` с автоматическим fallback на `ES 2.0`), внешняя текстура камеры `GL_TEXTURE_EXTERNAL_OES` (`SurfaceTexture`) для `Preview.SurfaceProvider` CameraX, одновременный вывод обработанного кадра на экранную `TextureView` и опциональную поверхность видеокодера `MediaCodec.createInputSurface()` с `EGLExt.eglPresentationTimeANDROID`, aspect-safe матрица `GlVisorMath.computeCenterCropMvp` (учитывающая 90°/270° поворот `stMatrix`), оффскринный `glReadPixels`-захват `captureSnapshotBitmap()` и автоматический fallback на CPU при недоступности EGL/GPU.
  - Реализованы GLSL-шейдеры (`FRAGMENT_300` + fallback `FRAGMENT_100`) для всех 12 безпамятевых/пространственных режимов `VisorMode.supportsGpuShader()` (`NORMAL`, `THERMAL_LUT`, `NIGHT_GAMMA`, `VEIN_HEMO`, `DERMA_UV_SIM`, `OCR_CONTRAST`, `DEHAZE_LIVE`, 3×3 Sobel `EDGE_TRACE`, `VEG_CHLOROPHYLL`, `IR_BEACON`, `SKIN_CHROMA`, `WIREFRAME_POSE` + второй проход `POSE_VERTEX`/`POSE_FRAGMENT` для отрисовки костей/суставов в превью и в MP4). Временные многокадровые режимы (`BIO_RPPG`, `EULER_AMP`) сохранены на CPU-пути.
  - В `VisorVideoRecorder.kt` добавлен режим `startForExternalSurface(...)` + `notifyExternalFramePushed()` + `stopAndFinalize(onBeforeSignalEos = ...)`, устраняющий копирование `Bitmap` на 25–30 FPS при записи GPU-визора и корректно отключающий `EGLSurface` до `signalEndOfInputStream()`.
  - В `CyberVisorEngine.kt` добавлен облегчённый метод `analyzeTelemetryOnly(...)` для обновления ML Kit Pose landmarks и телеметрии без генерации экранных `Bitmap`.
  - В `CameraOverlayComponents.kt` вынесен `CameraViewportSurface` (`TextureView` ↔ `PreviewView`), добавлена поддержка `SurfaceOrientedMeteringPointFactory` для tap-to-focus в GPU-режиме и `gpuVisorTelemetry` в `CameraTelemetryState`.
- **Тесты (`P5PolishAndGpuTest.kt`, новый файл):**
  - Добавлено 6 тестов: fast-path/стриппинг `VP8X` + `EXIF`/`XMP `/`ICCP` в WebP, `estimateSmartCompressionQuality` + `processBitmapClean`, `VoiceControlManager.parseCommand`, полнота 12 GPU-шейдеров + исключение временных режимов, математика `computeCenterCropMvp` (включая поворот 90° и `flipVertical`), и жизненный цикл `MuxerLifecycleGuard` / `VisorVideoRecorder`.

### 2. Верификация Шага А + Части 5 + Шагов Б/В
- **Команда:** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL**, **60/60 PASS** (`0 failures, 0 errors, 0 skipped` по 14 тестовым классам, включая `P5PolishAndGpuTest`: 6/6 PASS).
- **Сборка:** `compile_applet` -> **Build succeeded - the applet is compiled**.
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15):** реальное время сжатия WebP (`<200ms`), фактические FPS GPU-рендера `OpenGL ES 3.0` на Mali-G52 и запись MP4 25–30 FPS с GLSL-фильтрами без растяжения кадра — `UNVERIFIED` до проверки на телефоне.
- **Шаг Г (Keystore):** `debug.keystore` и `signingConfigs` не изменялись; пользователю выдано обязательное предупреждение о предварительном бэкапе старых `.gcf` файлов до перехода на релизную подпись.

---

## 2026-11-19 — P6 (Доработки по ТЗ GPT-6.2 Sol: Атомарный Сейф + Тайминги стадий, CLAHE LUT в GPU, PTS-синхронизация A/V, Изоляция прицела и Харденинг PanicKit)

### 1. Реализованные изменения
- **Атомарный Сейф и Профайлер стадий (`GhostCryptoVault.kt`, `CaptureManager.kt`, `CameraDiagnostics.kt`, `MainActivity.kt`):**
  - Добавлен метод `GhostCryptoVault.writeEncryptedAtomically(...)`: шифрование потока GCF1 (`MAGIC + 12B IV + AES-256-GCM`) сначала пишется в `.gcf.tmp` в том же каталоге и переименовывается в `.gcf` только после полного закрытия `CipherOutputStream` и финализации GCM-тега; обратная совместимость со всеми существующими 23+ файлами `.gcf` полностью сохранена.
  - Переведены `encryptBytesToVault`, `encryptFileToVault` и `CaptureManager.persistEncryptedCaptureResult` на атомарную запись.
  - Добавлен `GhostCryptoVault.shredOrphanedVaultTempFiles(...)` и подключён в `MainActivity.onResume` для автоочистки оборванных `.gcf.tmp` / нулевых `.gcf`.
  - В `CaptureManager` добавлен `CaptureStageTimings` (`captureRawMs`, `processEncodeMs`, `vaultEncryptMs`, `monoVaultMs`, `totalMs`); при сохранении `>= 500мс` статус-баннер автоматически показывает разбиение по стадиям (`raw=...ms proc=...ms aes=...ms mono=...ms`).
  - В `CameraDiagnostics` добавлен `buildDiagnosticJson(...)` (схема `zver-diag-v1`, без кадров, аудио, ключей и PII).
- **Исправление залипшего прицела и уровня «НАКЛОН» (`CameraOverlayComponents.kt`, `GhostCameraScreen.kt`):**
  - Введены чистые правила `shouldShowDocumentLevelOverlay` (`tab == "DOCUMENTS" && isDocumentModeActive && !isRecordingVideo`) и `shouldShowTacticalReticle` (`!isRecordingVideo && tab != "DOCUMENTS" && visorMode == VisorMode.NORMAL`).
  - Переключатель уровня в шторке при включении переводит на `"DOCUMENTS"` и при выходе/записи больше не оставляет «НАКЛОН» и прицел поверх `"Видео"` / `"Визор"`.
- **GPU `GlVisorDualRenderer` и `VisorVideoRecorder` (PTS-синхронизация, CLAHE 4×4 LUT, 0 аллокаций позы):**
  - В `GlVisorDualRenderer` добавлен `GlVisorPtsNormalizer`: устраняет рассинхрон часовых доменов между `SurfaceTexture.getTimestamp()` (HAL sensor clock) и `AudioRecord` (`System.nanoTime()`), приводя видео-PTS к строго монотонной шкале от `sessionStartNanoTime` из `VisorVideoRecorder.ExternalSurfaceSession`.
  - Добавлен `ClaheLutBuilder` (4×4 тайловые гистограммы Y-плоскости, clipLimit `2.4f`, компактная 2D-текстура `256×16` `GL_LUMINANCE`), загрузка `updateDehazeClaheLut(...)` из `ImageAnalysis` при активном `DEHAZE_LIVE` и билинейная интерполяция по 4 тайлам прямо во фрагментном шейдере GLES 3.0 (с автоматическим fallback на dark-channel).
  - Убраны покадровые нативные аллокации `ByteBuffer.allocateDirect` в `drawPoseWireframePass` (преаллоцированные `poseBoneFloatBuf` / `poseJointFloatBuf`).
  - В `VisorVideoRecorder.stopAndFinalize` поставлен атомарный `compareAndSet` на `isStoppingFlag` против двойного вызова `signalEndOfInputStream()`.
- **Безопасность `PanicTriggerReceiver` и диплинков `zver://` (`PanicTriggerReceiver.kt`, `MainActivity.kt`):**
  - Убран небезопасный строковый fallback (`caller_package`), позволявший стороннему приложению на API < 34 вызвать стирание Сейфа без ключа; теперь вайп допускается только при подтверждённом внешнем UID+SHA-256 сертификате из `ACTION_CONNECT` либо при совпадении `EXTRA_SHARED_SECRET` в постоянном времени; неподтверждённый триггер выполняет только безопасную блокировку RAM-сессии (`lockSession` + `clearRamShots` + сброс BYOK/Sentinel).
  - В `MainActivity.handleCustomUri` добавлен строгий allowlist известных маршрутов `zver://` (`capture`, `video`, `torch`, `stealth`, `lock`).
- **Тесты (`P6HardeningAndClaheTest.kt`, новый файл):**
  - Добавлено 6 JVM/Robolectric-тестов: `GlVisorPtsNormalizer`, `ClaheLutBuilder` + шейдеры `uDehazeLut`, правила видимости `DocumentLevelOverlay`/`TacticalReticle`, атомарная запись `.gcf.tmp -> .gcf` + `shredOrphanedVaultTempFiles`, защита `PanicTriggerReceiver` от спуфинга, `CameraDiagnostics.buildDiagnosticJson`.

### 2. Верификация P6
- **Команда:** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL**, **66/66 PASS** (`0 failures, 0 errors, 0 skipped` по 15 тестовым классам, включая `P6HardeningAndClaheTest`: 6/6 PASS).
- **Сборка:** `compile_applet` -> **Build succeeded - the applet is compiled**.
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15):** живая синхронизация звука и GPU-видео по `GlVisorPtsNormalizer`, визуальное качество 4×4 CLAHE LUT в режиме `Анти-Туман` на Mali-G52 и замер поэтапного времени сохранения WebP в статусе — `UNVERIFIED` до проверки на телефоне.

---

## 2026-11-21 — P7 (Золотая середина А–В: Release APK R8/CI + Голос Бурундук/Демон + Стилизованные Маски + Симбиоз Координатора)

### 1. Реализованные изменения
- **Подписанный Release APK + R8 + GitHub Actions (`gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `.github/workflows/build.yml`):**
  - В `buildTypes.release` включены `isMinifyEnabled = true` и `isShrinkResources = true` (без изменения `signingConfigs` / `debug.keystore`).
  - Добавлены R8-правила в `app/proguard-rules.pro` для CameraX/Camera2/Extensions, ML Kit JNI и `@UsedByNative`, Media3 `SonicAudioProcessor`/`Sonic`, RootBeer JNI, OkHttp 4.10 и Coroutines.
  - В `.github/workflows/build.yml` добавлена сборка `assembleRelease` наряду с `assembleDebug`: при наличии GitHub Secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`) используется постоянный ключ загрузки, иначе генерируется CI `release-upload.jks` (`alias=upload`); в артефакты и GitHub Release прикладываются `ZverCamera-<tag>-release.apk`, `ZverCamera-<tag>-debug.apk` и `mapping-<tag>.txt`.
- **Апгрейд офлайн-войсченджера (`OfflineVoiceChanger.kt`, `GhostVaultDialog.kt`):**
  - Подключён `androidx.media3:media3-common:1.5.1` (`SonicAudioProcessor` WSOLA — изменение высоты тона без сдвига темпа/длительности и роботизированного фазового смазывания, с автоматическим fallback на чистый Kotlin OLA).
  - Добавлены пресеты **`CHIPMUNK` (`"Бурундук 🐿"`, pitch `1.70x`)** и **`DEMON` (`"Демон 👹"`, pitch `0.56x`, кольцевая sub-модуляция `26 Гц`, мягкое насыщение `1.45x`)**; в `GhostVaultDialog` пресетом по умолчанию поставлен `CHIPMUNK`.
  - В `processMp4Offline` таймстампы AAC считаются строго от суммарного числа выходных сэмплов (`keepExactLength = false`), а в `transformPcm16Samples` сохранена обратная совместимость режима `keepExactLength = true`.
- **Стилизованные маски приватности и своя PNG-маска (`OfflinePrivacyEngine.kt`, `PrivacyEditorDialog.kt`):**
  - В `OfflinePrivacyEngine` добавлены `MaskPreset` (`CLOWN`, `WOLF`, `FOX`, `CAT`, `BEAR`, `SKULL`, `CUSTOM_PNG`), поле `rollDeg` из `Face.headEulerAngleZ` и `computeScaledMaskBounds(...)`.
  - Вместо старого чёрного прямоугольника с полоской реализован векторный полнолицевой Canvas-рендер (клоун с кудрями/колпаком/красным носом, волк, лиса, кот с усами, медведь, кибер-череп) и наложение прозрачной PNG-маски с сохранением пропорций и наклона головы.
  - В `PrivacyEditorDialog` подключён zero-permission `ActivityResultContracts.PickVisualMedia()` для выбора своей картинки/PNG (даунскейл `<= 1024px`, `ARGB_8888`, проверка альфа-канала), панель выбора масок и слайдер масштаба `60%–220%` с кнопками `−`/`+`.
- **Архитектурный симбиоз стабильности (`CameraBindingCoordinator.kt`, `CaptureManager.kt`, `GlVisorDualRenderer.kt`, `CameraOverlayComponents.kt`, `GhostCameraScreen.kt`):**
  - Создан `CameraBindingCoordinator.kt`: `CameraTab` и `CameraUiState` (единая изоляция оверлеев), `ThermalThrottleController` (слушатель `PowerManager.OnThermalStatusChangedListener` с динамическим интервалом анализа `230 / 350 / 650 / 1400 / 1800 мс` для MT6769Z), и `CameraBindingCoordinator` (`androidx.camera:camera-extensions:1.5.0` строго только для режима `NIGHT` без GPU-визора, генерационный токен и 1500 мс first-frame watchdog с безопасным откатом на базовый `CameraSelector`).
  - В `CaptureManager.kt` запись в 1 ГБ `MonoVaultEngine.appendRecord(...)` вынесена в сериализованную фоновую очередь `Dispatchers.IO.limitedParallelism(1)` с клонированием и последующим обнулением копии буфера, чтобы `fd.sync()` монолита не тормозил съёмку фото.
  - В `GlVisorDualRenderer.kt` поднят таймаут `detachEncoderSurfaceBlocking()` с `400 мс` до `1500 мс` и добавлен сигнал первого кадра `onFirstFrameRendered` для watchdog.
  - В `GhostCameraScreen.kt` и `CameraOverlayComponents.kt` подключены `CameraBindingCoordinator`, `ThermalThrottleController`, `CameraUiState`, наблюдение `PreviewView.previewStreamState == STREAMING` и фоновая отправка в `MonoVaultEngine`.
- **Тесты (`P7SymbiosisAndFeaturesTest.kt`):**
  - Добавлено 4 Robolectric-теста (`Media3 Sonic CHIPMUNK/DEMON`, нативный `@GraphicsMode(NATIVE)` рендер всех `MaskPreset` + `CUSTOM_PNG` + `computeScaledMaskBounds`, `CameraUiState` + допустимость `ExtensionsManager`, `ThermalThrottleController`).

### 2. Верификация P7
- **Команда:** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL**, **70/70 PASS** (`0 failures, 0 errors, 0 skipped` по 16 тестовым классам, включая `P7SymbiosisAndFeaturesTest`: 4/4 PASS).
- **Debug-сборка:** `compile_applet` -> **Build succeeded - the applet is compiled**.
- **Release R8-сборка:** `KEYSTORE_PATH=/tmp/ci_test.jks ... gradle :app:assembleRelease --console=plain` -> **BUILD SUCCESSFUL in 4m 54s** (`minifyReleaseWithR8` + `shrinkResources` пройдены без ошибок, временный `/tmp/ci_test.jks` удалён).
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15) и в GitHub Actions:** реальная работа Night Extension на HAL MT6769Z, качество звука Sonic на роликах из Сейфа, наложение стилизованных масок на реальных фото и выпуск артефактов на GitHub — `UNVERIFIED` до проверки пользователем.

---

## 2026-11-27 — Глубокий аудит камеры/жизненного цикла + Мост для чатов и ИИ (External Bridge & ZverClean Gallery Export)

### 1. Исправления по аудиту камеры, шифрования и памяти
- **Lifecycle и HAL Surface (`GhostCameraScreen.kt`, `CameraOverlayComponents.kt`):**
  - В `GhostCameraScreen` убран ранний `return` при активном `isStealthBlackoutActive`: `CameraViewportSurface` остаётся смонтированным под `StealthBlackoutScreen`, исключая уничтожение `SurfaceView`/`TextureView` и краш `BufferQueue has been abandoned` при активной привязке CameraX.
  - Заменена подписка `previewStreamState.observeForever` в `CameraOverlayComponents.kt` на lifecycle-привязанный `DisposableEffect` (`observe` + `removeObserver`) и добавлен `onRelease` сброс для `PreviewView`/`TextureView`; добавлен `activePreview?.setSurfaceProvider(null)` перед перепривязкой и в `ON_STOP`.
  - Остановка видеозаписи (`activeRecording`, `visorVideoRecorder`, `timeLapseRecorder`) перенесена с транзитного `ON_PAUSE` строго в `ON_STOP` (системные диалоги больше не обрывают запись); в `ON_STOP` корректные временные MP4 санитизируются и сохраняются с зафиксированным ключом/профилем (`encryptFileToVaultWithKey` + `pendingStopVideoKey` для HAL-видео), а возврат из фона (`ON_START` после `ON_STOP`) увеличивает `resumeBindToken`.
- **Консистентность ключей Сейфа и фото-захват (`GhostCryptoVault.kt`, `GhostVaultDialog.kt`, `CameraHudComponents.kt`, `GhostCameraScreen.kt`):**
  - Добавлены `hasUnlockedPinSession()`, `writeEncryptedAtomicallyWithKey(...)` и `encryptFileToVaultWithKey(...)`.
  - Убраны вызовы `lockSession()` при обычном закрытии `GhostVaultDialog`, чтобы последующие снимки из камеры не шифровались тихо в fallback `AndroidKeyStore` вместо разблокированного `MAIN` PBKDF2.
  - В `CameraTopHudAndDrawer` добавлен кликабельный баннер `pinSessionLockedWarning` (`"СЕЙФ БЛОК (KEYSTORE) • НАЖМИ ВВЕСТИ ПИН"`), а в `CaptureManager.takeSecurePhoto` передан `gpuSnapshotProvider`.
- **OOM-защита MP4 (`Mp4Sanitizer.kt`) и сборка (`.env.example`):**
  - Создан `.env.example` для Secrets Gradle Plugin.
  - Порог in-memory санитации MP4 снижен со 150 МБ до 48 МБ, добавлены проверка свободной heap-памяти (`length * 3L > freeHeap`) и перехват `OutOfMemoryError`.

### 2. Мост для чатов, Телеграма и ИИ-ассистентов (`ExternalBridgeActivity`, `BridgeManager`, `VaultCleanExporter`)
- **`BridgeManager.kt` + `AndroidManifest.xml` (`ExternalBridgeActivity.kt`):**
  - Добавлены отключённые по умолчанию `<activity-alias>` `.ExternalCameraBridgeAlias` (`IMAGE_CAPTURE`, `IMAGE_CAPTURE_SECURE`, `VIDEO_CAPTURE`) и `.ExternalPickerBridgeAlias` (`GET_CONTENT`, `PICK` для `image/*`, `video/*`) без добавления разрешений на чтение хранилища (`READ_EXTERNAL_STORAGE`/`READ_MEDIA_*` отсутствуют).
  - В `GhostSettingsDialog.kt` добавлен тумблер `"Мост для чатов и ИИ (Телеграм / Браузер)"`.
  - `ExternalBridgeActivity` позволяет прямо из внешнего приложения снять 0-EXIF фото или очищенный MP4 (с опцией дублирования в Сейф) либо по PIN выбрать ровно 1 файл из Сейфа через `FileProvider` (с авто-блокировкой сессии при выходе, если ранее не была разблокирована).
- **`VaultCleanExporter.kt` + `GhostVaultDialog.kt` + `GhostSettingsDialog.kt`:**
  - Для Android 13–15 System Photo Picker добавлен экспорт одного выбранного элемента кнопкой `"В ГАЛЕРЕЮ"` в `Pictures/ZverClean` / `Movies/ZverClean` (zero-permission Scoped Storage `IS_PENDING`, гарантированный 0-EXIF/санитизированный MP4) и кнопка быстрой зачистки `"🧹 УБРАТЬ КОПИИ ZverClean ИЗ ГАЛЕРЕИ"`.

### 3. Верификация
- **Тесты (`AuditAndExternalBridgeTest.kt` + полный прогон):** `gradle :app:testDebugUnitTest --console=plain` -> **BUILD SUCCESSFUL in 3m 37s**, **115/115 PASS** (`0 failures, 0 errors, 0 skipped` по 24 тестовым классам, включая `AuditAndExternalBridgeTest`: 5/5 PASS).
- **На физическом устройстве Redmi 13C (`23108RN04Y`, Android 15):** поведение HyperOS Camera HAL при Stealth Blackout/ON_STOP и системный chooser Телеграм/браузера — `UNVERIFIED` до проверки на устройстве.
