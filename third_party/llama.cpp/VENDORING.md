# Vendored llama.cpp

Upstream: https://github.com/ggml-org/llama.cpp

| Поле | Значение |
|---|---|
| Release | `v0.7.0` |
| Commit | `620d2225cc436ab0b001e3200e590e3b286ef9ab` |
| Источник | source archive релиза, `llama.cpp-620d2225...tar.gz` |
| SHA-256 архива | `1D4A987D9BD048F6E93107E838026438DF70FBDE237FC0C69C63D600512B4BD9` |
| Лицензия | MIT (см. `LICENSE`), лицензии зависимостей — в `licenses/` |

## Что включено

Подмножество дерева релиза, необходимое для сборки runtime через CMake:

- `CMakeLists.txt`, `cmake/`, `ggml/`, `src/`, `include/`, `common/`, `vendor/`
- `licenses/`, `LICENSE`, `AUTHORS`, `README.md`

Не включены: `examples/`, `tools/`, `tests/`, `docs/`, `ci/`, `.github/`, `models/`,
скрипты и прочие каталоги, которые не участвуют в сборке библиотеки и `common`.

## Как собирается

`app/src/main/cpp/CMakeLists.txt` подключает этот каталог через `add_subdirectory` и
задаёт:

- `LLAMA_BUILD_COMMON=ON` — нужен `common_chat_templates_*`;
- `LLAMA_BUILD_TESTS`, `LLAMA_BUILD_EXAMPLES`, `LLAMA_BUILD_TOOLS`, `LLAMA_BUILD_SERVER` — `OFF`;
- `GGML_OPENMP=OFF`, `GGML_NATIVE=OFF` (сборка под `arm64-v8a` и `x86_64`), `GGML_CCACHE=OFF`,
  `GGML_BACKEND_DL=OFF`.

`GGML_BACKEND_DL=OFF` важен: динамическая загрузка бэкендов не находит `.so` внутри APK,
поэтому CPU-бэкенд линкуется статически.

## Обновление

1. Скачать source archive нового тега, сверить SHA-256 и зафиксировать commit.
2. Заменить содержимое этого каталога тем же подмножеством файлов (не добавлять `examples/`,
   `tools/`, `tests/`).
3. Обновить таблицу выше и проверить, что `llama_jni.cpp` компилируется: API `llama_*`
   между релизами меняется (например, `llama_model_params` и сигнатуры `llama_tokenize`).
4. Собрать `:app:externalNativeBuildDebug` и прогнать приложение на устройстве/эмуляторе.

Локальных правок в файлах upstream нет; все изменения живут в `app/src/main/cpp/llama_jni.cpp`.
