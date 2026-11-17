# MASTER_SPEC.md — Единая техническая спецификация и аудит ZVER CAMERA

> **Дата ревизии:** 2026-11-17  
> **Базовый пакет:** `com.aistudio.ghostsuite.vkxpqz` (код в `namespace = "com.example"`)  
> **Конвенция достоверности:**  
> - `BUILD VERIFIED` — подтверждено компиляцией Gradle  
> - `TEST VERIFIED` — подтверждено unit / Robolectric-тестами (на данный момент: **25/25 PASS**)  
> - `DEVICE VERIFIED` — подтверждено на физическом Xiaomi Redmi 13C (в облачной среде агента ADB/эмулятора нет -> только по итогам тестов Сани на устройстве)  
> - `UNVERIFIED` — расчётные/оценочные цифры в комментариях и паспорте, не замерявшиеся физическим профайлером

---

## 1. Честный аппаратный паспорт целевого устройства (Xiaomi Redmi 13C)

### 1.1. Исправление ошибки в документации проекта (`MT6768` vs `MT6769Z`)
- В `README.md` (строки 4, 18) и `AntiBlurSensorEngine.kt` (строка 16) ранее было ошибочно указано `MT6768` (это Helio G80/G88 ранних ревизий).
- Целевое устройство **Xiaomi Redmi 13C (`23108RN04Y`)** на **MediaTek Helio G85** принадлежит к семейству **MT6769Z / MT6769V-CZ** (2× Cortex-A75 @ 2.0 GHz + 6× Cortex-A55 @ 1.8 GHz, GPU Mali-G52 MC2, 4 ГБ LPDDR4X, 128 ГБ eMMC 5.1, Android 15 / API 35, HyperOS 2.0.209, без root).
- Точное значение SoC в приложении должно считываться динамически на устройстве (`Build.SOC_MODEL`, `Build.HARDWARE`, `Build.BOARD`), а не зашиваться константой в текст.

### 1.2. План живого `Camera2 CameraCharacteristics` отчёта
Заявленные в `README.md` и `GhostSettingsDialog.kt` параметры («матрица 50МП», `4080×3072`, фиксированная частота и т.п.) должны проверяться в рантайме через диагностический модуль `CameraCharacteristics`:
1. `INFO_SUPPORTED_HARDWARE_LEVEL` (`LEGACY` / `LIMITED` / `FULL` / `LEVEL_3`) для каждой физической/логической камеры.
2. Допустимые комбинации одновременных потоков (`Preview + JPEG + YUV + Video` — на `LIMITED`/`LEGACY` устройствах MediaTek 4 одновременных поверхности часто выходят за гарантированную таблицу stream combinations!).
3. Реальные размеры сенсора (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`), список `SCALER_STREAM_CONFIGURATION_MAP` форматов (`JPEG`, `YUV_420_888`, `PRIVATE`) и фактическая доступность режима >12.5 МП.
4. Минимальная дистанция фокусировки (`LENS_INFO_MINIMUM_FOCUS_DISTANCE`), диапазоны ISO/выдержки, поддержка `CONTROL_AE_LOCK` / `CONTROL_AWB_LOCK`.

### 1.3. Маркировка неподтверждённых метрик (`UNVERIFIED`)
Следующие числа, фигурировавшие в `DOC-007` / `README.md` / комментариях кода, помечаются как **`UNVERIFIED`** до реального профилирования на физическом Redmi 13C:
- `CyberVisorEngine`: «2–4 ms на кадр Helio G85», «0% CPU overhead в режиме NORMAL» (`CyberVisorEngine.kt:67-68`).
- `AntiBlurSensorEngine` / `README.md`: «1–2 ms вычисление 400×400 Laplacian ROI».
- `LocalSymbiosisCvEngine`: «10–20 ms инференс 6-NN», точное потребление RAM/термальный троттлинг.
- `GhostSettingsDialog.kt:580`: «Основная матрица 50МП работает на полной детализации» (по факту CameraX `ImageCapture` по умолчанию снимает 12.5 МП 4-in-1 binning).
- `README.md:65`: «100% защита» от криминалистического анализа и PRNU (Anti-PRNU деформация `ExifStripper` не проходила лабораторный PRNU-тест корреляции сенсора).

---

## 2. Точные доказательства по P0-дефектам (зафиксированы до правки кода)

### P0-1: Ошибка расшифровки Сейфа (`AEADBadTagException`) на ~30 старых файлах `.gcf`
**Полная трассировка получения ключа, шифрования и расшифровки в `GhostCryptoVault.kt`:**
1. **Инициализация PIN (`setupVaultPins`, стр. 114–199):**
   - Генерируется 16 байт `salt`, вычисляется 256-битный KEK через `PBKDF2WithHmacSHA256` (600 000 итераций).
   - Генерируется случайный 256-битный AES-ключ `vaultAesKey`, оборачивается KEK (`AES/GCM/NoPadding`, `WRAP_MODE`, 12B IV) -> записывается в `salt_main.dat` + `wrapped_main.key` (аналогично `decoy` / `duress`).
   - В ОЗУ кэшируется `activeMainKey = vaultAesKey`.
2. **Разблокировка (`unlockWithPinOutcome`, стр. 205–263):**
   - Из `salt_main.dat` + `wrapped_main.key` через PIN-кек разворачивается `vaultAesKey` -> пишется в `activeMainKey`, `activeProfile = MAIN`.
3. **Авто-блокировка при сворачивании (`MainActivity.onStop`, стр. 125–130):**
   - Когда `ZverPrefs.isAutoLockOnBackground(this) == true` (значение по умолчанию!), вызывается `GhostCryptoVault.lockSession()` (стр. 624–628): `activeMainKey = null`, `activeDecoyKey = null`, `activeProfile = MAIN`.
4. **КОРЕНЬ СБОЯ — Тихая подмена ключа (`getActiveVaultKey`, стр. 318–322):**
   ```kotlin
   fun getActiveVaultKey(context: Context): SecretKey {
       val key = if (activeProfile == VaultProfile.MAIN) activeMainKey else activeDecoyKey
       if (key != null) return key
       return getHardwareKeystoreKey(context, "Ghost_Vault_${activeProfile.name}")
   }
   ```
   - Если пользователь снимает фото/видео **ДО** настройки PIN или **ПОСЛЕ** возврата из фона (когда `onStop()` обнулил `activeMainKey`, а Сейф ещё не открывали), `openEncryptedOutputStream()` / `encryptBytesToVault()` / `encryptFileToVault()` берут ключ из **AndroidKeyStore (`Ghost_Vault_MAIN`)** вместо PIN-обёрнутого ключа!
   - Когда позже пользователь открывает `GhostVaultDialog` и вводит PIN, `unlockWithPinOutcome()` записывает в `activeMainKey` **PBKDF2-развёрнутый ключ**.
   - Далее клик по любому файлу вызывает `decryptToRam()` (стр. 476) или `decryptToEphemeralFile()` (стр. 647) -> использует `activeMainKey` (PBKDF2-ключ) к шифротексту, зашифрованному ключом `Ghost_Vault_MAIN` -> **неминуемый `javax.crypto.AEADBadTagException`**!
   - Вторая зона риска: строка 470 в `encryptFileToVault` — `try { cipherOut.close() } catch (_: Exception) {}` глушит исключение финализации GCM-тега на видео.
5. **Обязательные ограничения по восстановлению:**
   - **ЗАПРЕЩЕНО** перезаписывать оригиналы `.gcf` без отдельного подтверждения пользователя.
   - **ЗАПРЕЩЕНО** делать автоматический тихий перебор/фолбэк на другой ключ при любой `AEADBadTagException` в штатном просмотре Сейфа.
   - Решение в P0: Read-only диагностика (определение источника ключа без модификации файлов + резервное копирование + тесты восстановления на копиях). Миграция/перешифрование 30 реальных файлов выполняется ТОЛЬКО после отдельного подтверждения.

### P0-2: CameraX — одновременные UseCase и зависания/ошибки на съёмке
**Фактическая схема привязки в `GhostCameraScreen.kt` (стр. 785–968):**
1. Основной `try` (стр. 914–930) пытается одновременно привязать **4 UseCase**:
   `Preview` + `ImageCapture (MINIMIZE_LATENCY + Camera2Interop AE antibanding & AF continuous)` + `ImageAnalysis (YUV_420_888, KEEP_ONLY_LATEST)` + `VideoCapture<Recorder> (Quality.HD)`.
2. При исключении срабатывает Catch #1 (стр. 931–948): биндит `Preview + ImageCapture + VideoCapture` — **молча выбрасывает `ImageAnalysis`** (ломаются Визор, Фокус-пикинг, Экспозиция, Кибер-Дозор без уведомления!).
3. При повторном исключении Catch #2 (стр. 949–966): биндит только `Preview + ImageCapture` (ломается видео!).
4. При открытии `VikaAiSheet` поверх экрана как полноэкранного `Dialog` вызывается `onCaptureTwoStageFrame` -> `CaptureManager.captureRawJpeg()` на том же `imageCapture` без переключения режима и без визуального предпросмотра кадра.
5. **Требуемый шаг:** Не голословно винить «только MediaTek HAL», а:
   - Добавить диагностику `CameraCharacteristics` (уровень железа + максимум потоков) и лог фактически привязанной комбинации UseCase.
   - Разделить конфигурации привязки для `PHOTO / VISOR / SENTINEL / DOCUMENTS` (`Preview + ImageCapture + ImageAnalysis`) и `VIDEO` (`Preview + VideoCapture`), исключив одновременное удержание 4 потоков там, где оно не нужно.

### P0-3: Краш при смене иконки (Icon Masking)
**Доказательства из склеенного манифеста (`app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`):**
- Строка 3: `package="com.aistudio.ghostsuite.vkxpqz"` (`context.packageName` в рантайме).
- Строка 58: `<activity android:name="com.example.MainActivity" ...>`
- Строки 73, 87, 101:
  - `com.example.MainActivitySystem`
  - `com.example.MainActivityCalculator`
  - `com.example.MainActivityDefault`
- Ошибка в `CamouflageManager.kt:19-21`:
  `ComponentName(context.packageName, "${context.packageName}.$aliasSuffix")` формирует несуществующее имя компонента `com.aistudio.ghostsuite.vkxpqz.MainActivityCalculator` -> `PackageManager.setComponentEnabledSetting()` кидает `IllegalArgumentException`.
- Запрещено менять `namespace` или `applicationId` для обхода; правильное решение — указывать реальный класс `com.example.$aliasSuffix` в пакете `context.packageName` и проверять через `PackageManager.resolveActivity` в Robolectric-тесте.

### P0-4: Дефекты BYOK Gemini и UI (`ByokManager`, `GeminiByokClient`, `VikaAiSheet`, `GhostSettingsDialog`)
1. **Рассинхрон и потеря состояния:** `GhostSettingsDialog.kt:71` и `VikaAiSheet.kt:94` держат локальные `remember { mutableStateOf(...) }`, не читают актуальную модель после закрытия/открытия другого окна, а запросы моделей/генерации привязаны к `rememberCoroutineScope()` диалога (умирают при закрытии).
2. **Пагинация и журнал API:** `GeminiByokClient.fetchAvailableModels()` (стр. 112) обрезает пагинацию на `pages < 3` (`pageSize=100`), не ведёт очищенный от ключей журнал HTTP-запросов (URL без ключа, HTTP код, число моделей, nextPageToken, выбранная модель).
3. **Неподмена выбранной модели:** Модель, выбранная пользователем, никогда не должна молча подменяться при генерации без явного уведомления.
4. **Офлайн-кнопка в Аналитике:** `VikaAiSheet.kt:940` выставляет `enabled = hasKey && !isGenerating`, не давая запустить локальный офлайн-разбор статистики/малышей без ключа.
5. **Предпросмотр кадра для Вики:** `VikaAiSheet` отправляет захваченный камерой кадр «вслепую» без миниатюры.
6. **UI-наложения:**
   - `GhostSettingsDialog.kt:94-99`: нет `statusBarsPadding()` / `navigationBarsPadding()`, нижняя кнопка «ЗАКРЫТЬ НАСТРОЙКИ» наезжает на панель навигации.
   - `GhostSettingsDialog.kt:687`: 4 `AppBadge` (`Termux`, `Termux:API`, `Shizuku`, `MacroDroid`) без переноса/скролла — `MacroDroid` рвётся по одной букве в столбик.
   - `GhostCameraScreen.kt:1603`: баннер уведомлений с жёстким `padding(top = 86.dp)` перекрывает верхний HUD; `CyberVisorControlCard` (стр. 2297) не сворачивается.

---

## 3. Аудит приватности, сети и зависимостей APK

> **Критическое исправление документации:** Старый `README.md` (стр. 71) ошибочно утверждает, что разрешение `INTERNET` вырезано (`tools:node="remove"`). Фактически `INTERNET` и `ACCESS_NETWORK_STATE` **присутствуют** в `AndroidManifest.xml` (стр. 19–20) для opt-in BYOK Gemini.

### 3.1. Полный список разрешений в итоговом `merged_manifest` (Debug APK)
1. **Явные (из `app/src/main/AndroidManifest.xml`):**
   - `CAMERA`, `HIGH_SAMPLING_RATE_SENSORS`, `VIBRATE`, `RECORD_AUDIO`, `DETECT_SCREEN_RECORDING`, `INTERNET`, `ACCESS_NETWORK_STATE`
2. **Скрытые / транзитивно притянутые библиотеками (`merged_manifest:32-41`):**
   - `com.google.android.providers.gsf.permission.READ_GSERVICES` (от `play-services-basement`)
   - `android.permission.WAKE_LOCK` (от `WorkManager` / `DataTransport` / `Firebase`)
   - `android.permission.RECEIVE_BOOT_COMPLETED` (от `WorkManager RescheduleReceiver`)
   - `android.permission.FOREGROUND_SERVICE` (от `WorkManager SystemForegroundService`)
   - `com.aistudio.ghostsuite.vkxpqz.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

### 3.2. Какие компоненты инициализируются автоматически при старте приложения
Проверка `merged_manifest` (стр. 156–412) и дерева `gradle :app:dependencies`:
1. **`FirebaseInitProvider`** (`initOrder="100"`, `directBootAware="true"`) + `ComponentDiscoveryService`:
   - Подгружает регистраторы `firebase-ai`, `firebase-appcheck`, `firebase-appcheck-recaptcha`, `firebase-appcheck-debug`, `firebase-common`.
   - При этом в коде **нет ни одного вызова `Firebase` или `AppCheck`** (проверено `grep` по всему `app/src/main/java/`). Но транзитивно в APK тащатся `io.ktor:ktor-client-okhttp:3.0.3`, `play-services-recaptchabase:16.1.0`, `firebase-auth-interop`.
2. **`MlKitInitProvider`** (`initOrder="99"`) + `MlKitComponentDiscoveryService` + `MlKitRemoteWorkerService` (`:mlkit_acceleration_mini_benchmark`):
   - **КРИТИЧЕСКАЯ НАХОДКА ДЛЯ ПРИВАТНОСТИ:** Все 6 библиотек ML Kit (`com.google.mlkit:*`) транзитивно тянут **`com.google.android.datatransport:transport-backend-cct:2.3.3`** (`TransportBackendDiscovery`, `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver` — стр. 391–406 манифеста)!
   - Это транспорт телеметрии Google Clearcut (CCT). Пока `INTERNET` был удалён в ранних версиях, он не мог уйти в сеть. **Сейчас, когда `INTERNET` разрешён в APK**, при активации ML Kit может планироваться фоновая отправка диагностической телеметрии инициализации/бенчмарка ML Kit в Google через `JobScheduler`!
3. **`androidx.startup.InitializationProvider`**:
   - Автоматически инициализирует `WorkManagerInitializer` (`WorkManager`, `RemoteWorkManagerService`, `SystemJobService`, батарейные/сетевые `ConstraintProxy`), `EmojiCompatInitializer`, `ProcessLifecycleInitializer`, `ProfileInstallerInitializer`.
4. **Прочие неиспользуемые зависимости в `app/build.gradle.kts`:**
   - `androidx.room:room-runtime/ktx/compiler` -> добавляет `MultiInstanceInvalidationService` (при 0 `@Entity`/`@Dao` в коде).
   - `retrofit:2.12.0`, `converter-moshi`, `logging-interceptor`, `moshi-kotlin` -> не используются (`GeminiByokClient` использует чистый `OkHttpClient` с `cache(null)` и `org.json`).

### 3.3. Что может покидать приложение и честная граница `:core` vs `:assist`
- **По инициативе пользователя:** При нажатии отправки в `VikaAiSheet` или триаже `Sentinel` на `https://generativelanguage.googleapis.com/v1beta/...` уходят промпт, выбранная модель, заголовок `x-goog-api-key` и сжатые WebP/JPEG байты (без EXIF/GPS).
- **Потенциально автоматически (без явного действия пользователя в сети):** При инициализации/работе ML Kit библиотека `datatransport:transport-backend-cct` может пытаться отправить системную телеметрию ML Kit (метрики модели/устройства, не пользовательские фото) через `JobInfoSchedulerService`, т.к. `INTERNET` разрешён.
- **Граница `:core` vs `:assist` (`GhostAssistService`):**
  - В текущем коде `GhostAssistService` (`android:process=":assist"`) — пустой сервис без привязанной логики `GeminiByokClient`. Весь `OkHttpClient` работает прямо в основном UI-процессе!
  - Отдельный процесс `android:process=":assist"` в рамках одного `applicationId` работает под **тем же самым Linux UID**, имеет общий доступ к `filesDir`, `SharedPreferences` и `AndroidKeyStore`. Это изоляция кучи/ОЗУ от краша, **а не** песочница безопасности от чтения секретов процесса.

---

## 4. Исключения из DOC-004 (строго соблюдаются)
- Никаких Telegram-ботов / скрытой отправки в сторонние сервисы.
- Никакого фейкового OSINT-трекинга или вымышленных данных датчиков.
- Никаких неподтверждённых заявлений про NDK/C++/NEON (всё CV/алгоритмика сейчас на Kotlin/Java + CameraX + ML Kit).
- Никакой подмены/удаления работающих функций без явного решения.
