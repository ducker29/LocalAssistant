# multilingual-e5-small: проверка перед интеграцией

Дата: 2026-11-02. Статус: отдельный posfix GGUF создан; integrity/token parity/smoke/boundary checks PASS.
Пользователь разрешил выполнять этапы по порядку и останавливаться при вопросах/проблемах.

## Подтверждено источниками

- Оригинал: https://huggingface.co/intfloat/multilingual-e5-small
- Лицензия MIT по model card; 12 слоёв, hidden size 384, max positions 512 по config.json.
- Tokenizer — XLMRobertaTokenizer, несмотря на architectures=[BertModel]. Это существенный
  нюанс конверсии: наличие GGUF не доказывает корректность токенизации.
- Mean pooling: официальный 1_Pooling/config.json. Для retrieval обязательны `query: `
  и `passage: `, в том числе на русском, затем L2 normalization.
- 512 — полный бюджет с префиксом и специальными токенами, не число слов/символов.

## Текущий проект

- Vendored llama.cpp v0.7.0, commit 620d2225cc436ab0b001e3200e590e3b286ef9ab.
- В src/models/bert.cpp есть encoder graph, в include/llama.h — MEAN pooling и
  llama_get_embeddings_seq. В llama-vocab.cpp есть SentencePiece/Unigram tokenizer.
- JNI приложения сейчас только для чата, не выдаёт embeddings. Нужен отдельный владелец
  embedding model/context и последовательная работа, не переиспользование Qwen context.
- Предварительный путь: расширить JNI на существующей библиотеке без новых Gradle
  dependencies и без обновления llama.cpp. Совместимость ещё нужно подтвердить запуском.

## Предлагаемый артефакт

Repo: https://huggingface.co/TwinSunsLLC/multilingual-e5-small-gguf
Revision: `b6cac9615d4ecce28d7f22539b7322d695fc2886`
File: `multilingual-e5-small-q8_0.gguf`
Size: 132439008 bytes (около 126.3 MiB).
SHA-256 (HF LFS metadata): `e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93`
MIT указана автором конверсии. Это сторонняя конверсия, не официальный GGUF intfloat.

Автор описывает преобразование BertModel → XLMRobertaModel в config перед конверсией
и использование llama.cpp с mean pooling. Его заявления о качестве Q8 не являются
нашей проверкой. Другие найденные GGUF используют иные пайплайны, включая CrispEmbed fork;
их нельзя молча считать взаимозаменяемыми.

Q8_0 предлагается как начальная точка качества. Переход к Q4 — только после сравнения,
не автоматическая оптимизация по аналогии с генеративной Qwen.

## Ресурсы и проверка

- Файл весов ≠ пиковая RAM: дополнительно словарь, context/compute buffers, Qwen/Whisper/UI.
  Замеров RAM/скорости на CPH2581 пока нет; одновременную загрузку нельзя считать доказанной.
- Один нормализованный float32-вектор: 384*4=1536 bytes; 10000 векторов — 15.36 MB без
  текста, метаданных и структуры индекса. Для малого архива сначала достаточно exact scan.
- После отдельного разрешения скачать pinned файл вне Git, сверить размер/SHA-256,
  проверить GGUF metadata и совместимость текущего runtime, special tokens/token counts,
  384 finite components и норму ~1. Затем ранжирование русских query/passage с точными
  артикулами/числами и отрицательными примерами. Нужна проверка против reference tokenization.
- До успеха этой проверки не объявлять semantic search готовым и не индексировать архив
  непроверенными векторами. Произвольный фиксированный cosine threshold не выбирать заранее.

## Выполненная проверка

- Пользователь явно разрешил скачивание кандидата и проверку на компьютере.
- `models/multilingual-e5-small-q8_0.gguf` скачан по закреплённой revision.
  Размер132439008 и SHA-256 выше совпали. /models/ уже исключён из Git.
- Локальные вспомогательные файлы (тоже вне Git): models/inspect_e5_gguf.py и
  models/embedding-probe/{CMakeLists.txt,main.cpp,build/}.
- Host probe собран из существующего third_party/llama.cpp (без изменения vendor/app)
  установленными MSVC19.51, SDK CMake4.1.2/Ninja, CPU backend, 4 inference threads.
  Конфигурация: vcvars64.bat → cmake -S models/embedding-probe -B models/embedding-probe/build
  -G Ninja -DCMAKE_BUILD_TYPE=Release; SDK ninja задан CMAKE_MAKE_PROGRAM.
  Сборка: cmake --build models/embedding-probe/build --target e5_probe --parallel 6 — PASS.
- `models/embedding-probe/build/e5_probe.exe models/multilingual-e5-small-q8_0.gguf` — PASS:
  7 синтетических входов, 384 конечных float values, L2 unit norm, три query нашли правильный
  passage top1 среди четырёх кандидатов (оплата/артикул и цена/гарантия/нерелевантный рецепт).
  Cosines правильных пар: 0.856581, 0.935663, 0.898749. Это smoke, не оценка качества архива.
- Первый probe ошибочно требовал llama_model_has_encoder=true; по исходникам это API
  для другого пути моделей, BERT там false. Проверка исправлена. llama_decode с non-causal
  context штатно делегирует encode в этой ревизии. Это не несовместимость модели.
- GGUF: architecture bert, tokenizer t5/UGM, causal=false, mean=1, context_length=511.
  Runtime округлил requested context511 до512 и напечатал possible context overflow.
  Длинные входы не проверялись — нельзя использовать все512 без граничных проверок.
- GGUF mask_token_id=250001, но runtime печатает для него [PAD250000], не <mask>.
  Дефект метаданных требует разбора; не доказывает ошибку обычного retrieval (mask не нужен).

## Reference tokenizer — выполнено по отдельному разрешению

- Создано models/e5-verify-env через python -m venv; установлен tokenizers==0.22.2
  с его транзитивными dependencies только в этом окружении (Python3.13.12).
- Официальный tokenizer.json получен из intfloat/multilingual-e5-small,
  revision614241f622f53c4eeff9890bdc4f31cfecc418b3; локально models/e5-official-tokenizer.json.
  SHA256: 0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39.
- models/embedding-probe/main.cpp дополнен --tokens (vocab_only), UTF8 fixtures передаются
  как hex, без подстановок shell. Native harness пересобран успешно.
- `models/e5-verify-env/Scripts/python.exe models/verify_e5_tokenization.py` — FAIL,
  16/24 exact token ID matches. Отчёт: models/e5-token-parity-report.json (вне Git).
- Совпали: empty, русский вопрос/перефразирование/passage, английский, числа/артикул,
  Excel-подобная строка, NBSP, combining/fullwidth/ligature normalization, кириллица,
  emoji, mixed code, controls, CJK, пунктуация, длинное слово.
- 6 несовпадений связаны с trailing whitespace: prefix-only, whitespace case и 4 длинных
  boundary fixtures. Reference сохраняет конечный токен whitespace id6, GGUF его убирает.
  Boundary labels510/511/512/513 — reference token counts; фактически GGUF509/510/511/512.
  Это проверка токенизации, НЕ inference на длинных входах.
- Ещё 2 несовпадения — literal <mask> и <s>/</s>/<unk>/<pad>. Native test использует
  add_special=true, parse_special=false; reference распознаёт AddedTokens. Режимы пока
  не согласованы, поэтому это не доказательство плохих весов/сломанных обычных embeddings.
- Официальный vocab_size250002, <mask>=250001; GGUF vocab250037 и тот же ID является
  [PAD250000]. Обычные ID на проверенных строках совпадают; special mapping требует разбора.

## Повторная проверка по согласию пользователя

- Пользователь разрешил разбор и повторную проверку. Для поискового представления
  применены одинаковые правила на обеих сторонах: strip внешних whitespace и разделение
  literal reserved spellings <mask>/<s>/</s>/<unk>/<pad> пробелами внутри угловых скобок.
  Это подготовка search input, а не изменение исходных документов или весов модели.
- `models/e5-verify-env/Scripts/python.exe models/verify_e5_tokenization.py` — PASS24/24
  для ПОДГОТОВЛЕННОГО текста. Boundary cases заново построены под exact counts510..513.
  Это не доказывает совпадение RAW-токенизации (её отличия16/24 описаны выше).
- Прочитаны tensor descriptors исходного скачанного GGUF:
  position_embd.weight shape=[384,511], GGML type0/F32. Значит511 — реальное усечение
  positional tensor, а не только ограничение в metadata.
- Изучен upstream conversion/bert.py по pinned llama.cpp commit:
  https://raw.githubusercontent.com/ggml-org/llama.cpp/620d2225cc436ab0b001e3200e590e3b286ef9ab/conversion/bert.py
  _xlmroberta_tokenizer_init выставляет offset=1+pad_token_id и уменьшает max positions;
  XLMRobertaModel.modify_tensors обрезает первые offset строк positional weights.
  У исходного e5 config BertModel/pad0/max512, автор GGUF описывает rewrite в XLMRoberta.
  Это сильное объяснение observed511, но равенство строк оригиналу ещё не проверено.

## Positional comparison — выполнено по отдельному согласию

- Пользователь разрешил частичную загрузку и сравнение. Запуск:
  `python models/verify_e5_positions.py` — FAIL с ожидаемой диагностикой CONFIRMED shift.
- HTTP Range: 3 запроса, проверены206/Content-Range/Content-Length и жёсткий byte budget;
  заголовок safetensors и F32 positional tensor, суммарно808896 bytes. Полный файл не скачан.
- Официальная таблица shape512x384; GGUF511x384. Побайтово совпали ВСЕ511 строк GGUF
  со строками1..511 оригинала; со строками0..510 ни одна строка не совпала.
- Official positional tensor SHA256:
  3c87459adc43ea62d7f0dc722136e515d7d97ab60f33369d9ac305646a84ee89.
  GGUF positional tensor SHA256:
  d4221f951db32b799e58f1133378812bab5485079f2c551f92dcfe482b157c98.
- Локальные результаты вне Git: models/e5-official-position-header.json,
  models/e5-official-position-f32.bin, models/e5-position-comparison.json.
- Это подтверждает сдвиг сохранённых positional weights, а не количественную оценку потери
  retrieval quality. Русский smoke3/3 мог пройти и при этой ошибке; не считать его parity.

## Исправленная копия — выполнено по согласию пользователя

- Пользователь разрешил отдельную corrected copy и проверки. Воспроизводимый скрипт:
  `tools/embedding/repair_e5_positions.py` (stdlib, без downloads/dependencies).
- Команда `python tools/embedding/repair_e5_positions.py` — PASS. Скрипт строго проверяет
  source/positions hash+size и refuses overwrite. Исходный GGUF оставлен неизменным.
- Файл: models/multilingual-e5-small-q8_0-posfix.gguf, размер132440544 bytes (+1536).
  SHA256: bdb785b2ca8532ff3097f93ac3e4e1d560a122cfba377bf559895aebfd9be6d9.
- Полная F32 positional table512x384 заменяет сдвинутую511x384; context metadata512,
  descriptor shape/offsets скорректированы с соблюдением alignment32. Все196 остальных
  tensors проверены побайтово: идентичны. Raw metadata/tokenizer arrays не изменены,
  кроме context uint32. Отчёт: models/multilingual-e5-small-q8_0-posfix.verification.json.
- Это производный локальный артефакт, не официальный GGUF intfloat и не исходный файл автора.
- Host harness пересобран из того же pinned runtime, model context512; logging ограничен
  WARN/ERROR. Token fixture mode и основное inference не изменяют входные weights.
- `models/e5-verify-env/Scripts/python.exe models/verify_e5_tokenization.py` — PASS24/24
  на posfix GGUF после ранее описанной search-only подготовки. Сырые различия не исчезли.
- `models/embedding-probe/build/e5_probe.exe models/multilingual-e5-small-q8_0-posfix.gguf`
  — PASS3/3 Russian retrieval, finite384/unit norm, repeated-input stability PASS.
  Correct-pair cosine:0.855904/0.933483/0.894527. Не интерпретировать эти числа как порог.
- Та же команда с `--boundary models/e5-boundary-fixtures.hex` — PASS: настоящее inference
  для510/511/512 total tokens (включая prefix/specials);513 отклонён до batch/decode.
  Это защита host harness, ещё НЕ реализованная защита Android JNI.
- Предупреждение model vocab missing newline token остаётся; проверенные prepared inputs
  с newline совпадают с reference. Не считать модель генеративной/не использовать её chat API.

## Текущий результат и следующий этап

Исходный кандидат со сдвигом не использовать для индекса. Posfix прошёл перечисленные host
checks и может быть начальным кандидатом для Android-интеграции. Полная numerical parity
с official full-precision моделью и качество реального архива ещё НЕ проверены.

Отдельный JNI model/context и main-safe Kotlin owner теперь реализованы:
embedding_runtime.cpp,embedding_jni.cpp и embedding/*.kt. MEAN/noncausal,L2/384,512-token
preflight, pinned hash/size, один worker/Mutex, abort-cancel, finished-before-free и idempotent
close. Qwen context не переиспользуется; backend init shared once, не free при unload.
По отдельному разрешению assembleDebug и E5EmbeddingEngineTest PASS(6 tests), обе ABI;
host production-core probe PASS. Device JNI/память/скорость не проверены, UI/model import/
AppContainer ещё не подключены. Следующий блок — chunking/index и lifecycle integration.
Устройство/установка/перенос weights — только по отдельному запросу.
Оригинал intfloat/model.safetensors по pinned revision614241f622f53c4eeff9890bdc4f31cfecc418b3,
полный размер470641600, LFS sha1a55775f53449dac10a2bcbc312469fac40b96d53198c407081a831f81c98477.
Полный размер/хеш — справочная provenance, partial download не позволяет проверить полный hash.
Веса в приложение не интегрированы; код app/vendor/версии не менялись. Gradle/device
commands не выполнялись; документы пользователя не использовались/не отправлялись.
