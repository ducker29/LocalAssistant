# Передача контекста проекта

## Состояние на 2026-10-26
**Phase 1 коммит создан локально** на `master`: `7b1d27a` —
`feat: verified speech input, transcription, and chat persistence`.
В коммите: исходники `:app`, юнит-тесты, Gradle, `docs/`, и вендоренные
`third_party/llama.cpp` (v0.7.0) + `third_party/whisper.cpp` (2049 файлов) —
вендоренные источники обязательны для воспроизводимых локальных сборок.
Исключены: `whisper/` (CMake-модуль сборки), `.idea/`, `diagnostics/`, а также
`models/`, `*.gguf.part`, кэши, секреты. **Remote не настроен, коммит не пушен**
(по требованию). Рабочее дерево сохранено: в нём остались незакоммиченные
instrumented-тесты `app/src/androidTest/.../asr/{WhisperEngineTest,
WhisperCancellationTest,NativeHandoffTest}.kt` (не входят в Phase 1).

## Состояние на 2026-10-25
M3 (STT через whisper.cpp) **реализована в исходнике и проверена на эмуляторе**.
Модуль `:whisper` (NDK-сборка вендоренного whisper.cpp) подключён к `:app`.
Реализован JNI-мост `whisper_jni.cpp` с отменой через `AtomicBoolean` и `abort_callback`
(троттлинг убран). Реализованы: запись 16 кГц (fallback 48/44.1), лимит 60 с, черновик
при уходе в фон, вставка в поле ввода без автоотправки, скачивание/импорт/удаление
модели, фильтр неречевых меток, отмена записи и распознавания.

Проверено автоматизированными тестами на эмуляторе: транскрипция фикстуры
(`WhisperEngineTest`), Qwen→Whisper→Qwen (`NativeHandoffTest`), отмена
(`WhisperCancellationTest` — 3/3 PASS: 4899/5328/4888 мс), юнит-тесты M3
(13 тестов, 0 failures, 2026-10-24 20:34:56), lint (0 errors, 22 warnings,
2026-10-24 20:39:02). `assembleDebug` — успешно (APK 2026-10-25 15:23:52).

Проверено пользователем вручную (эмулятор): запись реального голоса через микрофон
хоста, транскрипция, вставка распознанного текста в редактируемый черновик.

Скачивание модели через UI — проверено проверкой агента/рантайма (аудит, §5).

Не проверялось: явная отправка черновика в Qwen из редактора, микрофон реального
arm64-телефона, количественная точность распознавания на заданном наборе.
Изменения Whisper закоммичены в Phase 1 коммит `7b1d27a` (2026-10-26, по явному
разрешению пользователя); remote не настроен, пуш не выполнялся.

Подробности по отмене — в `diagnostics/cancellation-20261025_nothrottle_run{1,2,3}/`.
Подробности M3 — в `docs/IMPLEMENTATION_REPORT.md` §11–18.

## Состояние на 2026-10-24
M1 закрыта, M2 почти закрыта: офлайн-чат на Qwen3-1.7B Q4_K_M работает на эмуляторе
через собранный llama.cpp. Последние проверки: `assembleDebug` — успешно,
`testDebugUnitTest` — 25 тестов без падений, `lintDebug` — 0 ошибок (21 warning).
Подробности и ограничения — в `docs/STATE.md`.

Инференс проверен вручную на эмуляторе: `Hello` → `Hello! How can I assist you today?`,
история пережила перезапуск. Дополнительно проверены многоходовый контекст, «Стоп» до и
во время генерации, генерация после отмены, «Новый чат», скачивание модели в приложении
(SHA-256 сошёлся), импорт GGUF через SAF, генерация без сети. Подробная таблица
PASS/NOT TESTED, методика замеров и инструкция для телефона:
`docs/IMPLEMENTATION_REPORT.md`. На реальном телефоне не проверялось; APK на телефон
не устанавливался.

## Как воспроизвести проверку на эмуляторе
1. Сборка: `.\gradlew.bat :app:assembleDebug`.
2. Установка: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
3. Подготовка модели (веса в репозиторий не входят, `models/` в `.gitignore`):
   - `adb push models\Qwen_Qwen3-1.7B-Q4_K_M.gguf.part /data/local/tmp/model.gguf`
   - `adb shell "run-as com.example.localassistant mkdir -p files/models"`
   - `adb shell "run-as com.example.localassistant cp /data/local/tmp/model.gguf files/models/model.gguf"`
   - `adb shell "rm -f /data/local/tmp/model.gguf"`
   - сверить SHA-256: `72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`.
4. Запуск: `adb shell am start -n com.example.localassistant/.MainActivity`.
5. Логи движка: `adb logcat | Select-String "LlmNative|LlamaEngine"`.

Emulator: `emulator-5554`, x86_64, API 37. Для 1.28-ГБ модели нужен запас RAM;
эмулятор запускался с `-memory 6144`.

## Доступные артефакты
- `third_party/llama.cpp` — вендоренная v0.7.0 (commit `620d2225`), лицензия MIT,
  происхождение и SHA-256 архива: `third_party/llama.cpp/VENDORING.md`.
- `models/Qwen_Qwen3-1.7B-Q4_K_M.gguf.part` — полная копия весов (1 282 439 584 байт)
  только на этой машине; можно переименовать в `.gguf` или пушить как есть.
- `Qwen_Implement.md` — исходное задание; в Git не добавлен.

## Проверенные команды
```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```
Перед использованием убедиться, что модуль `:app` и задачи существуют.
Git-коммит: `7b1d27a` на `master` (Phase 1, локально, remote не настроен).
`.gitignore` содержит `/models/`, `*.gguf.part`, `whisper/`, `.idea/`, `diagnostics/`.

## Хрупкие места
- `abort_callback` в `whisper_jni.cpp` вызывает JNI-метод `get` на `AtomicBoolean` при
  **каждом** вызове. Это безопасно: callback вызывается один раз на шаг декодирования
  (не на токен), а чтение boolean — дешёвая операция. Троттлинг (mod-8) **не использовать**:
  он добавлял до ~5 с задержки отмены (диапазон 9920–15721 мс) против ~5 с без него (4888–5328 мс).
- `llama_tokenize` этой ревизии сигнализирует нехватку буфера отрицательным числом;
  размерный проход с `nullptr, 0` всегда возвращает отрицательное значение — это норма,
  а не ошибка. Не «исправлять» обратно.
- `llama_model_params` не имеет `use_mmap`: используется `load_mode`.
- Kotlin-логи в release-сборке отсутствуют по правилам R8; для диагностики — debug.
- PowerShell 5.1: `Set-Content -Encoding UTF8` добавляет BOM и ломает TOML/JSON —
  писать через `[IO.File]::WriteAllText(..., UTF8Encoding($false))`.
- `adb exec-out screencap -p > file` в PowerShell 5.1 портит PNG; использовать
  `adb shell screencap -p /sdcard/s.png` + `adb pull`.

## Открытые задачи
1. Прогнать на реальном телефоне по `docs/IMPLEMENTATION_REPORT.md` §9 и заполнить таблицу
   §5 фактическими данными; APK на телефон не ставить без разрешения.
2. Согласовать исправление дефектов из §7 отчёта: автоскролл к новому ответу, потеря
   частичного текста при убийстве процесса, недоступность импорта/удаления при готовой модели.
3. ~~M3 (STT)~~: реализована и проверена на эмуляторе. Остаётся: микрофон реального
    arm64-телефона, количественная точность распознавания на заданном наборе, явная
    отправка черновика в Qwen из редактора. Изменения Whisper закоммичены в
    Phase 1 коммит `7b1d27a` (по явному разрешению); remote не настроен, пуш не выполнялся.
4. Проверить STT на реальном телефоне (arm64-v8a); текущие замеры — только
   x86_64-эмулятор.
5. M4: локальные заметки (добавление, чтение, изменение, удаление через UI).

## Инструментация (debug)
`LlmNative` пишет `model load took N ms`, `prompt ready`, `first token after N ms`, RSS в кБ,
`generation finished: N tokens … (X tok/s)`; `ChatViewModel` — `first visible token after N ms`
и `turn finished in N ms, chars=N, cancelled=…`. Тексты сообщений в логи не пишутся.

## Как воспроизвести проверку отмены STT (whisper)
1. Сборка: `.\gradlew.bat :app:assembleDebug` (собирает и `:whisper`).
2. Установка: `adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk`.
3. Подготовка модели (веса в репозиторий не входят):
   - `adb -s emulator-5554 push <путь к ggml-base.bin> /data/local/tmp/whtmp/ggml-base.bin`
   - `adb -s emulator-5554 shell "run-as com.example.localassistant sh -c 'mkdir -p files/whisper && cat /data/local/tmp/whtmp/ggml-base.bin > files/whisper/ggml-base.bin'"`
   - `adb -s emulator-5554 shell "rm -rf /data/local/tmp/whtmp"`
   - сверить MD5: `335f34f382e396519b6359d32c786317`.
   - **Важно (наблюдаемое, не подтверждённое объяснение):** при каждом запуске
     `connectedDebugAndroidTest` в этой сессии модель отсутствовала, и
     `run-as com.example.localassistant` возвращал `unknown package`. Модель приходилось
     восстанавливать каждый раз: `adb install -r` → `adb push` → `run-as … cat >`.
     Не утверждается, что Gradle **универсально** удаляет приложение. Путь:
     `files/whisper/ggml-base.bin` в пакете `com.example.localassistant`.
4. Запуск только целевого теста:
   `.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.localassistant.asr.WhisperCancellationTest'`
5. Результаты: `app\build\outputs\androidTest-results\connected\debug\TEST-*.xml` и
   `...\logcat-*.txt`. Ключевые строки: `CANCEL_RESULT stopAfterFlagMs=…`,
   `RECOVERY_RESULT`, `DIAG whisper_full returned code=…`, `DIAG abort observed by native callback invocation=…`.

## Что не делать без подтверждения
`git add`, `git commit`, `git push`, установка APK, удаление модели или истории диалога,
добавление разрешений в Manifest, новые зависимости, апгрейд Gradle/AGP/Kotlin/NDK/CMake.
