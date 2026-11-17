# IMPLEMENTATION_PLAN.md — Поэтапный план работ (P0 → P4)

> **Принцип работы:** Сначала доказательства, безопасность и обратимость; минимальные локальные правки без удаления фич и без ломки существующих тестов.  
> **Запреты на весь цикл:**
> - Запрещено перезаписывать оригиналы 30 файлов `.gcf` или запускать массовое перешифрование без отдельного подтверждения пользователя после тестов на копиях.
> - Запрещён молчаливый автоматический fallback на другой ключ при `AEADBadTagException`.
> - Запрещено менять `namespace` (`com.example`), `applicationId` (`com.aistudio.ghostsuite.vkxpqz`), `debug.keystore` и `signingConfigs`.
> - Запрещено тихо подменять выбранную пользователем модель Gemini без уведомления.

---

## Этап 0: Единая документация и аудит (Stage 0)
- **Статус:** Завершение прямо сейчас (7/7 документов в корне репозитория).
- **Артефакты:** `DOCUMENT_INDEX.md`, `MASTER_SPEC.md`, `REQUIREMENTS_TRACEABILITY.md`, `ARCHITECTURE_DECISIONS.md`, `IMPLEMENTATION_PLAN.md`, `WORK_LOG.md`, `IMPLEMENTATION_STATUS.md`.
- **Exit Criteria:** Все функции `DOC-007` классифицированы по 6 статусам, все P0-баги доказаны точными ссылками на код и `merged_manifest`, выполнен полный аудит зависимостей и приватности.

---

## Этап P0: Критические исправления (Blockers) и безопасная диагностика

### P0-A: Маскировка иконки (`CamouflageManager`)
- **Причина (доказана):** `CamouflageManager.kt:19-21` строит `ComponentName` с `pkg = com.aistudio.ghostsuite.vkxpqz`, тогда как в `merged_manifest` (`AndroidManifest.xml:58,73,87,101`) алиасы объявлены в `com.example.MainActivity*`.
- **Правка:**
  - `CamouflageMode.getComponentName(context)`: использовать package класса `MainActivity` (`"com.example"`), сохраняя `context.packageName` первым аргументом `ComponentName`.
  - Проверить `getCurrentCamouflage(context)` с учётом `COMPONENT_ENABLED_STATE_DEFAULT` для `.MainActivityDefault` (`android:enabled="true"` в манифесте) и `COMPONENT_ENABLED_STATE_ENABLED` после переключения.
- **Тест:** Добавить Robolectric-тест в `ZverSecurityFeaturesTest.kt` (или `CamouflageManagerTest.kt`), проверяющий `PackageManager.getActivityInfo` / `resolveActivity` и переключение всех 3 режимов без `IllegalArgumentException`.

### P0-B: Сейф — Read-Only диагностика, резервное копирование, восстановление на копиях
- **Причина (доказана):** `GhostCryptoVault.getActiveVaultKey()` (`GhostCryptoVault.kt:318-322`) при `activeMainKey == null` молча возвращает аппаратный ключ `AndroidKeyStore` (`Ghost_Vault_MAIN`), из-за чего файлы записывались разными ключами. Кроме того, `encryptFileToVault` (`GhostCryptoVault.kt:470`) проглатывает исключения `cipherOut.close()`.
- **Правка (строго обратимая):**
  1. Добавить **read-only инспектор заголовков** `inspectGcfHeader(file)`: размер файла, валидность magic `"GCF1"`, длина, извлечение IV (без расшифровки и без изменения файла).
  2. Добавить **read-only диагностику источника ключа** `diagnoseDecryptKeySource(context, file)` — ТОЛЬКО для явной диагностики/процедуры восстановления: проверяет, каким из 4 контуров (`activeMainKey`, `activeDecoyKey`, `KeyStore Ghost_Vault_MAIN`, `KeyStore Ghost_Vault_DECOY`) файл реально расшифровывается в память, **ничего не меняя на диске**.
  3. Добавить **резервное копирование каталога сейфа** `backupVaultDirectoryReadOnly(sourceDir, backupDir)` (копирование без удаления/перезаписи оригиналов) и **восстановление в копию** `recoverDecryptedCopy(...)` / `recoverVaultDirectoryToCopy(...)`.
  4. Штатные `decryptToRam()` и `decryptToEphemeralFile()` **ни в коем случае не делают автоматического fallback** на другой ключ при `AEADBadTagException` — пробрасывают честную ошибку с диагностической подсказкой (какой ключ был активен и что возможен key-mismatch до ввода PIN).
  5. Исправить `encryptFileToVault` (`GhostCryptoVault.kt:470`): не глотать исключение при `cipherOut.close()`.
  6. Добавить диагностическую сводку в `GhostVaultDialog` (read-only кнопка/панель диагностики сейфа + безопасный бэкап, без автоперешифрования 30 файлов!).
- **Тест:** Unit/Robolectric тесты на временных копиях (`VaultRecoveryDiagnosticTest.kt`): симуляция файлов, зашифрованных `Ghost_Vault_MAIN` и `PBKDF2 PIN`, проверка `inspectGcfHeader`, проверка отказа штатного `decryptToRam` на чужом ключе (`AEADBadTagException` без silent fallback), проверка read-only диагностики, бэкапа и восстановления на копиях без изменения оригиналов.

### P0-C: BYOK Gemini (`Вика AI`) — единое состояние, пагинация, HTTP-логи, офлайн-аналитика, предпросмотр кадра
- **Причины (доказаны):** Дубли локального `remember`-стейта в `GhostSettingsDialog` и `VikaAiSheet`; отмена `rememberCoroutineScope` при закрытии окна; лимит пагинации `pages < 3`; молчаливая подмена модели; блокировка кнопки аналитики при `!hasKey`; отсутствие предпросмотра кадра и HTTP-журнала.
- **Правки:**
  1. В `ByokManager.kt`: ввести `StateFlow` (`selectedModelFlow`, `discoveredModelsFlow`, `hasKeyFlow`, `isFetchingModelsFlow`, `lastModelFetchStatusFlow`, `httpLogsFlow`), долгоживущий `CoroutineScope(SupervisorJob() + Dispatchers.IO)` для `refreshAvailableModelsAsync(...)`, и санитаризованный кольцевой журнал HTTP-запросов (никогда не логирует `apiKey` и заголовки `x-goog-api-key`).
  2. В `GeminiByokClient.kt`: расширить пагинацию (до 10 страниц с защитой от зацикливания `nextPageToken`), логировать endpoint, HTTP код, длительность мс, число моделей / ошибку (очищенную от ключей) для `models` и `generateContent`. Никогда молча не подменять `selectedModel` при генерации — при неверной модели возвращать честный ответ HTTP/модели.
  3. В `GhostSettingsDialog.kt` и `VikaAiSheet.kt`: подписаться через `collectAsStateWithLifecycle()` на единый `ByokManager`; убрать тихую автозамену выбранной пользователем модели; добавить просмотр и копирование очищенного HTTP-журнала.
  4. В `VikaAiSheet.kt`: добавить миниатюру захваченного кадра (`ImageBitmap` / `BitmapFactory`) перед отправкой (с возможностью сбросить/переснять) и разблокировать кнопку локальной аналитики (`"РАЗБЕРИ СТАТИСТИКУ"`) для офлайн-отчёта `VaultAnalytics` при отсутствии ключа/сети.
- **Тест:** Unit-тесты на санитаризацию HTTP-логов (маскировка любых ключей `AIza...` и заголовков), `StateFlow` синхронизацию и отсутствие тихой подмены модели.

### P0-D: CameraX — разделение режимов и диагностика Camera2 HAL
- **Причины (доказаны):** Одновременная привязка 4 UseCase (`Preview + ImageCapture + ImageAnalysis + VideoCapture`) в `GhostCameraScreen.kt:785-968` + немые catch-фолбэки + неверный паспорт SoC (`MT6768` вместо `MT6769Z`) + отсутствие реального опроса `CameraCharacteristics`.
- **Правки:**
  1. Создать `com.example.camera.CameraDiagnostics`: реальный опрос `Build.SOC_MODEL` / `Build.HARDWARE` / `CameraManager.getCameraCharacteristics()` (уровень `INFO_SUPPORTED_HARDWARE_LEVEL`, активная матрица, макс. JPEG/YUV размеры, `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, `CONTROL_AE_LOCK_AVAILABLE`, `CONTROL_AWB_LOCK_AVAILABLE`). Исправить комментарии `MT6768` -> `MT6769Z` в `AntiBlurSensorEngine.kt` и `README.md`.
  2. Переделать биндинг CameraX в `GhostCameraScreen.kt`: ключ `LaunchedEffect(lensFacing, isVideoMode)` где `isVideoMode = (selectedCameraMode == "VIDEO")`. В режиме `VIDEO` биндить `Preview + VideoCapture` (2 потока), в фото/оптических режимах — `Preview + ImageCapture + ImageAnalysis` (3 потока). Добавить явную телеметрию привязанных UseCase (без тихих потерь `ImageAnalysis`).
  3. Вывести читаемый диагностический блок `Camera2 / HAL` в `GhostSettingsDialog` (с кнопкой копирования в буфер) для получения реальных логов с Redmi 13C.

### P0-E: UI Полировка и Insets (`GhostSettingsDialog`, `GhostCameraScreen`, `CyberVisorControlCard`)
- **Правки:**
  1. `GhostSettingsDialog.kt`: добавить `.statusBarsPadding().navigationBarsPadding().imePadding()`, заменить `Row` у 4 `AppBadge` (`Termux`, `Termux:API`, `Shizuku`, `MacroDroid`) на `FlowRow` с `softWrap = false`, чтобы `MacroDroid` не рвался по буквам.
  2. `GhostCameraScreen.kt`: убрать наезд баннера `padding(top = 86.dp)` на верхний HUD (использовать структурированный `Column` с `statusBarsPadding()` или безопасный отступ под верхним рядом кнопок).
  3. `CyberVisorControlCard`: добавить кнопку сворачивания/разворачивания (`▲ / ▼`) с `Modifier.testTag("visor_card_toggle_button")` и 48dp touch target, чтобы не перекрывать экран видоискателя.

---

## Этап P1: Живая верификация на Xiaomi Redmi 13C (Device Testing)
- Собрать и установить APK на физический Redmi 13C (или прогнать пользователем по чеклисту):
  1. Снять реальный `CameraDiagnostics` отчёт (подтвердить фактический `HARDWARE_LEVEL` — `LEGACY`/`LIMITED`/`FULL`, макс. стримы, `50MP` vs `12.5MP` биннинг).
  2. Проверить стабильность переключения `PHOTO <-> VISOR <-> VIDEO` и запись MP4 + звук.
  3. Проверить переключение иконок `Default -> Calculator -> System Tools` в лаунчере HyperOS 2.0.
  4. Проверить read-only диагностику на реальных 30 старых файлах `.gcf` (выяснить точный ключ каждого файла: `Ghost_Vault_MAIN` vs `PBKDF2`), затем по отдельному решению выполнить бэкап + безопасное восстановление.
  5. Прогнать BYOK Gemini `models` (увидеть реальные HTTP-коды) + предпросмотр кадра + офлайн аналитику.

---

## Этап P2: Гигиена приватности, вычистка SDK и ревизия MonoVault / MP4
- Отключить/удалить неиспользуемые зависимости из `app/build.gradle.kts` (`firebase-ai`, `firebase-appcheck-*`, `room-*`, `retrofit`, `moshi`) после подтверждения, что они не ломают проект -> убрать `FirebaseInitProvider`, `AppCheck`, лишние сервисы из `merged_manifest`.
- Оценить политику ML Kit vs `transport-backend-cct` (при желании полностью локального контура без Clearcut — решить, отключать ли telemetry transport манифестом или заменять/ограничивать `INTERNET` отдельным тумблером/манифестным правилом).
- Решить судьбу `GhostAssistService` (`:assist`): либо убрать мёртвую заглушку, либо оформить честный AIDL/Intent клиент без ложных обещаний полной безопасности.
- Проверить `MonoVaultEngine` (1 ГБ `raf.setLength` на eMMC 5.1 и интеграцию чтения с `GhostVaultDialog`) и `Mp4Sanitizer` для 64-битного `co64`.

---

## Этап P3: CI/CD и выдача APK в GitHub Releases
- Обновить `.github/workflows/build.yml`, чтобы при пуше / ручном запуске (`workflow_dispatch`) или тегах `v*` создавался понятный версионированный Release (а не только `nightly` тег), прикладывался подписанный/debug APK с понятным именем (напр. `ZverCamera-vX.Y.apk`) и разделялись артефакты для пользователя vs отладки.

---

## Этап P4: Оптимизация перформанса, UX-полировка и профилирование
- Замерить реальный FPS `CyberVisorEngine` и 6-NN ML Kit на Helio G85 (`MT6769Z`, 4 ГБ ОЗУ), заменить все оставшиеся `UNVERIFIED` метрики реальными цифрами или честными диапазонами.
- Довести `50MP` high-res capture / Camera2 extensions (если поддерживается HAL Redmi 13C согласно `CameraDiagnostics`).
