# IMPLEMENTATION_STATUS.md — Текущий статус реализации и верификации

> **Условные обозначения уровней проверки:**
> - `BUILD VERIFIED` — код скомпилирован через Gradle без ошибок.
> - `TEST VERIFIED` — подтверждено локальным unit или Robolectric JVM-тестом.
> - `DEVICE VERIFIED` — подтверждено на физическом Xiaomi Redmi 13C (`23108RN04Y`, HyperOS 2.0.209).
> - `UNVERIFIED` — не проверено живым запуском/профилированием на устройстве.

---

## 1. Общая сводка состояния (Stage 0 + P0 → P4 завершены в коде)

- **Сборка Debug (`compile_applet`):** `BUILD VERIFIED` (API 35: `compileSdk = 35`, `targetSdk = 35`, `coreKtx = 1.15.0`)
- **Локальные тесты (`gradle :app:testDebugUnitTest --rerun-tasks --console=plain`):** `TEST VERIFIED` — **43/43 PASS** (`0 failures, 0 errors, 0 skipped` по 11 тестовым классам)
- **Документация Stage 0 + журналы:** **7/7 актуальны** (`DOCUMENT_INDEX.md`, `MASTER_SPEC.md`, `REQUIREMENTS_TRACEABILITY.md`, `ARCHITECTURE_DECISIONS.md`, `IMPLEMENTATION_PLAN.md`, `WORK_LOG.md`, `IMPLEMENTATION_STATUS.md`)
- **Живая проверка на Xiaomi Redmi 13C (`23108RN04Y`, HyperOS 2.0.209):** `UNVERIFIED` (аппаратные тесты Camera2 HAL, микрофона Audio Watcher, лаунчера HyperOS, Gemini BYOK HTTP и старых 30 `.gcf` файлов выполняются на живом устройстве)

---

## 2. Матрица Этапов P0–P4 (текущий статус)

| Этап | Область | Реализованные модули | Верификация |
|---|---|---|---|
| **P0** | Маскировка иконки (`CamouflageManager`), строгий `.gcf` + read-only диагностика/бэкап, BYOK `Вика AI` логи/модели, разделение CameraX + `CameraDiagnostics` (`MT6769Z`), Edge-to-Edge insets & сворачиваемый HUD | `CamouflageManager.kt`, `GhostCryptoVault.kt`, `GhostVaultDialog.kt`, `ByokManager.kt`, `VikaAiSheet.kt`, `CameraDiagnostics.kt`, `GhostCameraScreen.kt`, `GhostSettingsDialog.kt` | `BUILD VERIFIED` + `TEST VERIFIED` (`P0DiagnosticsAndFixesTest` 3/3 PASS); на Redmi 13C — `UNVERIFIED` |
| **P1** | Pro Camera2 HAL профайлер (`CameraCapabilityProfiler`), `PhotoIqEngine`, `ZoomIntelligence`, Live Гистограмма и Зебра (`CameraHudOverlays`), неразрушающий редактор Сейфа (`VaultPhotoEditorDialog`), секция `1B` в настройках, Camera2 Interop | `CameraCapabilityProfiler.kt`, `PhotoIqEngine.kt`, `ZoomIntelligence.kt`, `CameraHudOverlays.kt`, `VaultPhotoEditorDialog.kt`, `GhostSettingsDialog.kt`, `GhostCameraScreen.kt` | `BUILD VERIFIED` + `TEST VERIFIED` (`P1ProCameraAndIqTest` 5/5 PASS); реальный Camera2 HAL / FPS — `UNVERIFIED` |
| **P2** | Multi-Frame Burst (`MultiFrameStacker`: `NIGHT_DENOISE`, `HDR_FUSION`, `DETAIL_RECOVERY`), интеллектуальное сжатие (`ZverPrefs`, `ExifStripper`, `CaptureManager`), удаление неиспользуемых SDK (Firebase/Room/Retrofit/Moshi), блокировка Clearcut (`tools:node="remove"`), честный `GhostAssistService`, UI-вкладка `МОНОЛИТ`, `Mp4Sanitizer.co64` | `MultiFrameStacker.kt`, `ExifStripper.kt`, `Mp4Sanitizer.kt`, `ZverPrefs.kt`, `CaptureManager.kt`, `GhostAssistService.kt`, `GhostVaultDialog.kt`, `app/build.gradle.kts`, `AndroidManifest.xml` | `BUILD VERIFIED` + `TEST VERIFIED` (`P2BurstAndPrivacyTest` 4/4 PASS, `ZverSanitizerUnitTest` 3/3 PASS, `VaultIntegrityUnitTest` 4/4 PASS); живое устройство — `UNVERIFIED` |
| **P3** | Лаборатория плат PCB (`PcbInspectorEngine`, `PcbLabDialog`: SMD/EIA-96/4-band, мостики припоя, перегрев/прогары), Акустик-сторож (`AudioWatcherEngine`), 2 новых режима Био-Визора (`EDGE_TRACE`, `OCR_CONTRAST` — итого 13), версионированный CI-релиз APK (JDK 21, `setup-java@v5`, `setup-gradle@v3`, API 35, `action-gh-release@v2`) | `PcbInspectorEngine.kt`, `PcbLabDialog.kt`, `AudioWatcherEngine.kt`, `CyberVisorEngine.kt`, `GhostCameraScreen.kt`, `GhostVaultDialog.kt`, `.github/workflows/build.yml` | `BUILD VERIFIED` + `TEST VERIFIED` (`P3PcbAndAudioTest` 4/4 PASS); реальный микрофон/PCB камера и GitHub Actions — `UNVERIFIED` |
| **P4** | Верификация откреплённых ProofMode-доказательств (`verifyDetachedProofJson` в Сейфе, кнопка `ПРОВЕРИТЬ`), пакетный Chain-of-Custody манифест (`generateVaultChainManifestJson` -> кнопка `⛓️ ЦЕПЬ`, `chain_manifest.json` с SHA-256 цепочкой и ECDSA-подписью без изменения оригиналов `.gcf`), аудит честности справки (`GhostSettingsDialog` — убран миф про 50MP) | `GhostCryptoVault.kt`, `GhostVaultDialog.kt`, `GhostSettingsDialog.kt` | `BUILD VERIFIED` + `TEST VERIFIED` (`P4ChainOfCustodyTest` 2/2 PASS); живые файлы на Redmi 13C — `UNVERIFIED` |

---

## 3. Живой чеклист верификации на Redmi 13C (для Сани)
1. **GitHub Actions / APK:** После пуша в `main` скачать `ZverCamera-v1.0.<N>.apk` из GitHub Releases и установить на Redmi 13C.
2. **Camera2 HAL + Pro Режим:** Открыть Настройки → раздел диагностики Camera2 HAL, скопировать реальный отчёт сенсоров и проверить переключение `PHOTO ↔ VIDEO`, Pro HAL / IQ / Гистограмму / Зебру и Multi-Frame стек.
3. **Сейф (30 старых `.gcf`) + Chain-of-Custody:** Сначала нажать `📦 БЭКАП (КОПИЯ)`, затем `🩺 КЛЮЧИ` на старых файлах (оригиналы не тронутся), проверить `⛓️ ЦЕПЬ` и `ПРОВЕРИТЬ` на новом ProofMode-снимке.
4. **Маскировка иконки (HyperOS 2.0):** Проверить переключение Default / Calculator / System Tools и возврат по `PIN + =`.
5. **PCB Лаб + Акустик-сторож + Вика AI:** Проверить микрофонный индикатор `AudioWatcher`, осмотр платы в `PCB ЛАБ` и живой HTTP-лог в `Вика AI` при наличии ключа.

