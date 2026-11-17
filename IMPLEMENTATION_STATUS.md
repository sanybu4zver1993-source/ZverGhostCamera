# IMPLEMENTATION_STATUS.md — Текущий статус реализации и верификации

> **Условные обозначения уровней проверки:**
> - `BUILD VERIFIED` — код скомпилирован через Gradle без ошибок.
> - `TEST VERIFIED` — подтверждено локальным unit или Robolectric JVM-тестом.
> - `DEVICE VERIFIED` — подтверждено на физическом Xiaomi Redmi 13C (`23108RN04Y`, HyperOS 2.0.209).
> - `UNVERIFIED` — не проверено живым запуском/профилированием на устройстве.

---

## 1. Общая сводка состояния (после завершения Этапа P0)

- **Сборка Debug (`compile_applet` + `:app:testDebugUnitTest`):** `BUILD VERIFIED` (актуальный код после всех правок P0)
- **Локальные тесты (`gradle :app:testDebugUnitTest --rerun-tasks`):** `TEST VERIFIED` — **28/28 PASS** (`0 failures, 0 errors, 0 skipped`)
- **Документация Stage 0 + журналы:** **7/7 актуальны** (`DOCUMENT_INDEX.md`, `MASTER_SPEC.md`, `REQUIREMENTS_TRACEABILITY.md`, `ARCHITECTURE_DECISIONS.md`, `IMPLEMENTATION_PLAN.md`, `WORK_LOG.md`, `IMPLEMENTATION_STATUS.md`)
- **Живая проверка на Xiaomi Redmi 13C после исправлений:** `UNVERIFIED` (ожидает прогона Саней по чеклисту Этапа P1)

---

## 2. Матрица P0-блокеров (текущий статус)

| ID | Область | Суть проблемы | Статус фикса | Верификация |
|---|---|---|---|---|
| **P0-A** | Маскировка иконки (`CamouflageManager`) | Неверный пакет в `ComponentName` (`com.aistudio.ghostsuite.vkxpqz.MainActivity*` вместо `com.example.MainActivity*`) | **Исправлено** (`CamouflageManager.kt`) | `BUILD VERIFIED` + `TEST VERIFIED` (`P0DiagnosticsAndFixesTest` 3/3 алиаса); работа лаунчера HyperOS 2.0 — `UNVERIFIED` |
| **P0-B** | Сейф (30 старых `.gcf`, `AEADBadTagException`) | Смешение PIN-ключа и `Ghost_Vault_MAIN` при `activeMainKey == null`; глушение ошибки закрытия потока | **Безопасная диагностика + бэкап + строгий запрет фолбэка** (`GhostCryptoVault.kt`, `GhostVaultDialog.kt`) | `BUILD VERIFIED` + `TEST VERIFIED` (read-only инспектор, строгий `AEADBadTagException`, бэкап и восстановление на копиях; оригиналы не тронуты); разбор реальных 30 файлов на Redmi 13C — `UNVERIFIED` |
| **P0-C** | Вика AI / BYOK Gemini | Рассинхрон состояния, отмена запроса при закрытии диалога, лимит 3 стр., нет HTTP-лога, нет превью кадра, заблокирована офлайн-аналитика | **Исправлено** (`ByokManager.kt`, `GeminiByokClient.kt`, `VikaAiSheet.kt`, `GhostSettingsDialog.kt`) | `BUILD VERIFIED` + `TEST VERIFIED` (санитаризация ключей, HTTP-лог, запрет тихой подмены модели); живые ответы Gemini API — `UNVERIFIED` |
| **P0-D** | CameraX + Паспорт железа | 4 одновременных UseCase + немой фолбэк; неверная метка `MT6768`; нет `CameraCharacteristics` диагностики | **Исправлено** (`CameraDiagnostics.kt`, `GhostCameraScreen.kt`, `AntiBlurSensorEngine.kt`, `README.md`) | `BUILD VERIFIED`; живой `CameraCharacteristics` отчёт и стабильность HAL на Redmi 13C — `UNVERIFIED` |
| **P0-E** | UI Insets и перекрытия HUD | Отсутствуют insets в `GhostSettingsDialog`; перенос `MacroDroid`; наезд `86.dp` баннера; не сворачивается `CyberVisorControlCard` | **Исправлено** (`GhostSettingsDialog.kt`, `GhostCameraScreen.kt`) | `BUILD VERIFIED`; визуальная проверка на экране Redmi 13C — `UNVERIFIED` |

---

## 3. Следующие этапы (P1–P4)
- **P1 (Device Verification на Redmi 13C):** Прогон 5 пунктов чеклиста Саней (отчёт `Camera2 HAL`, фото/видео переключение, маскировка иконки, read-only диагностика + бэкап 30 `.gcf` файлов, живой HTTP-лог Gemini).
- **P2 (Приватность и чистка SDK):** Удаление неиспользуемых зависимостей (`firebase-ai`, `firebase-appcheck-*`, `room-*`, `retrofit`, `moshi`) и решение по `datatransport-cct` / `GhostAssistService` / `MonoVaultEngine` / `Mp4Sanitizer co64`.
- **P3 (GitHub Releases):** Версионированный релиз APK в `.github/workflows/build.yml` (не только `nightly`).
- **P4 (Профайлинг MT6769Z и 50MP):** Реальные замеры FPS и проверка `UltraHiRes` по данным из `CameraDiagnostics`.
