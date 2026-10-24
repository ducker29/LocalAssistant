# Передача контекста проекта

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
Перед использованием убедиться, что модуль `:app` и задачи существуют. Git-коммитов нет;
`.gitignore` дополнен `/models/` и `*.gguf.part`.

## Хрупкие места
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
3. Согласовать M3: запись аудио, whisper.cpp, транскрипция, правка текста перед отправкой.

## Инструментация (debug)
`LlmNative` пишет `model load took N ms`, `prompt ready`, `first token after N ms`, RSS в кБ,
`generation finished: N tokens … (X tok/s)`; `ChatViewModel` — `first visible token after N ms`
и `turn finished in N ms, chars=N, cancelled=…`. Тексты сообщений в логи не пишутся.

## Что не делать без подтверждения
`git add`, `git commit`, `git push`, установка APK, удаление модели или истории диалога,
добавление разрешений в Manifest, новые зависимости, апгрейд Gradle/AGP/Kotlin/NDK/CMake.
