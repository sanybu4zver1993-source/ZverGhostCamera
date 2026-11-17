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

