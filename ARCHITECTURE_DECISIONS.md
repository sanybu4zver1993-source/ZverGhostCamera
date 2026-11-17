# ARCHITECTURE_DECISIONS.md — Архитектурные решения (ADR)

## ADR-001: Политика ключей Сейфа и запрет автоматического перебора при `AEADBadTagException`
- **Контекст:** В `GhostCryptoVault.getActiveVaultKey()` при `activeMainKey == null` происходило переключение на `AndroidKeyStore` ключ `Ghost_Vault_MAIN`, что породило 2 несовместимых класса шифротекстов `.gcf` (часть зашифрована PIN-ключом, часть — `Ghost_Vault_MAIN`).
- **Решение:**
  1. Никакого автоматического тихого фолбэка на другой ключ при `AEADBadTagException` в штатном открытии файла! Любая ошибка аутентификации тега GCM должна честно сообщаться пользователю с указанием ожидаемого источника ключа (PIN-контур vs Hardware KeyStore fallback).
  2. Любая операция восстановления старых 30 файлов `.gcf` обязана быть отдельной, **read-only диагностируемой**, с обязательным бэкапом перед записью и предварительным прогоном на копиях в unit/Robolectric-тестах. Ни один оригинал не перезаписывается без отдельного подтверждения пользователя.
  3. Для новых записей, когда Сейф уже настроен (`isConfigured(context)`), необходимо чётко различать активный разблокированный PIN-ключ от фолбэка (либо требовать разблокировку PIN для записи в PIN-сейф / явно разделять контекст ключа с версией формата или диагностическим флагом без потери совместимости).

## ADR-002: Разделение конфигураций CameraX для PHOTO/VISOR/SENTINEL и VIDEO + диагностика Camera2
- **Контекст:** В `GhostCameraScreen.kt` привязка 4 одновременных UseCase (`Preview + ImageCapture + ImageAnalysis + VideoCapture`) в catch-ветках молча выкидывает `ImageAnalysis` или `VideoCapture`. На бюджетных Camera2 HAL (вкл. MediaTek) комбинация из 4 потоков не гарантируется спецификацией `LIMITED`.
- **Решение:**
  1. Ввести отдельный диагностический отчёт `CameraCharacteristics` (hardware level, максимальное число потоков, поддерживаемые форматы, min focus distance, наличие `Build.SOC_MODEL`), чтобы получать факты с устройства без гаданий.
  2. Разделять активные UseCase по выбранному режиму съёмки:
     - Режимы фото/оптики (`PHOTO`, `NIGHT`, `VISOR`, `SENTINEL`, `DOCUMENTS`, `VOICE`, `STEALTH`): `Preview + ImageCapture + ImageAnalysis`.
     - Режим видео (`VIDEO`): `Preview + VideoCapture` (плюс при возможности/необходимости без `ImageAnalysis` и `ImageCapture`, чтобы освободить HAL-буферы).
  3. При любом фолбэке явно отображать в диагностике, какие UseCase фактически привязаны.

## ADR-003: Исправление `CamouflageManager` строго по `merged_manifest` без правки `namespace`/`applicationId`
- **Контекст:** `applicationId = "com.aistudio.ghostsuite.vkxpqz"`, `namespace = "com.example"`. Склеенный манифест резолвит `<activity-alias android:name=".MainActivityCalculator">` как `com.example.MainActivityCalculator`, а `CamouflageManager` искал `com.aistudio.ghostsuite.vkxpqz.MainActivityCalculator`.
- **Решение:** Фиксировать компонент через `ComponentName(context.packageName, "com.example.$aliasSuffix")`, не меняя `namespace` и `applicationId`, и добавить Robolectric-тест на разрешение всех 3 алиасов через `PackageManager`.

## ADR-004: Единый источник правды BYOK (StateFlow), неразрываемый scope и честный HTTP-лог
- **Контекст:** Диалоги настроек и Вики хранили локальные копии модели/списка в `remember`, запросы обрывались при закрытии диалога, не было логов API и миниатюры кадра.
- **Решение:**
  1. Хранить реактивное состояние BYOK (`selectedModel`, `discoveredModels`, `hasKey`, `isFetchingModels`, `sanitizedHttpLogs`) централизованно в `ByokManager` через `StateFlow` и читать в обоих диалогах.
  2. Выполнять `fetchAvailableModels` на долгоживущем `CoroutineScope` с полной пагинацией (сняв жёсткий потолок `pages < 3` -> безопасный верхний предел например 10 страниц) и вести in-memory кольцевой лог HTTP-вызовов с жёсткой санитаризацией ключа API.
  3. Никогда не подменять выбранную пользователем модель другой моделью без явного уведомления (удалить скрытые автопереключения; обновлять модель при первом вводе ключа только если ранее выбранная отсутствует или пользователь нажал выбор, с явным статус-сообщением).
  4. Разблокировать кнопку анализа локальной статистики в `VikaAiSheet` и в офлайн-режиме (через локальный отчёт `VaultAnalytics`, а в Gemini — при наличии ключа и сети).

## ADR-005: Честные границы процесса `:assist` и аудит сторонних SDK
- **Контекст:** В `DOC-007` и комментариях кода `android:process=":assist"` описывался как «полная изоляция от камеры и ключей».
- **Решение:** Привести документацию в соответствие с архитектурой Android:
  - `android:process=":assist"` разделяет только память (ART heap) в рамках **одного и того же Linux UID**, а сейчас вообще является пустым сервисом-заглушкой (`GeminiByokClient` вызывается из главного процесса).
  - Неиспользуемые зависимости (`firebase-ai`, `firebase-appcheck-*`, `room-*`, `retrofit`/`moshi`) и скрытый `transport-backend-cct` в ML Kit зафиксированы в аудите приватности и запланированы к безопасному удалению/сдерживанию в отдельной задаче (P2), чтобы не ломать текущую сборку на этапе P0.

## ADR-006: Строгая маркировка верификации
- **Решение:** Никогда не смешивать локальную сборку/тесты с работой на реальном телефоне. Все заявления без живых замеров на Redmi 13C маркируются `UNVERIFIED`; результаты делятся на `BUILD VERIFIED`, `TEST VERIFIED` и `DEVICE VERIFIED`.
