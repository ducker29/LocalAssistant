# Журнал решений

## D001 — Локальное Android-приложение
Статус: выбрано пользователем.
Решение: Kotlin/Compose, локальная Qwen3-1.7B GGUF Q4_K_M или Q4_0.
Причина: ассистент для базы знаний, календаря и голосовых запросов без обязательного облачного инференса.
Открыто: сравнение квантов на целевом телефоне; версия и способ интеграции runtime.

## D002 — Раздельные STT и LLM
Статус: принято и реализовано (2026-10-24); runtime — whisper.cpp.
Решение: аудио преобразуется в текст перед передачей текстовой LLM; распознанный текст
попадает в редактируемое поле ввода и отправляется только по явному нажатию.
Открыто: реальная микрофонная запись и скорость на телефоне.

## D003 — Файловые контрольные точки
Статус: выбран подход настройки агента.
Решение: AGENTS.md, STATE.md, HANDOFF.md и два локальных skill.
Ограничение: не гарантирует сохранение перед аварийным завершением. Требует регулярного обновления и проверки по коду.

## D004 — llama.cpp вендорится в репозиторий
Статус: принято (2026-10-24).
Контекст: нужен локальный инференс GGUF на arm64 и x86_64 без облака и без сборки на телефоне.
Решение: `third_party/llama.cpp`, release `v0.7.0` (commit `620d2225`), MIT; сборка через
CMake, CPU-бэкенд статически (`GGML_BACKEND_DL=OFF`), `LLAMA_BUILD_COMMON=ON` для Jinja-шаблонов.
Альтернативы: Maven/AAR-обёртки llama.cpp (меньше контроля над ревизией), динамическая
загрузка бэкендов (не находит `.so` в APK), собственная сборка в CI (позже).
Последствия: ~34 МБ исходников в репозитории; обновление — вручную по `VENDORING.md`.
Проверка: `:app:externalNativeBuildDebug`, запуск на эмуляторе, загрузка модели в логах.

## D005 — Артефакт модели и пиннинг
Статус: принято (2026-10-24).
Контекст: официальный репозиторий Qwen публикует только Q8_0, а для телефона нужен Q4.
Решение: `bartowski/Qwen_Qwen3-1.7B-GGUF`, ревизия `dcb19155...`, файл
`Qwen_Qwen3-1.7B-Q4_K_M.gguf`, 1 282 439 584 байт, SHA-256 `72c5c3cb...`.
Альтернативы: Q4_0 (быстрее, чуть хуже качество), Q8_0 (слишком большая для RAM телефона).
Последствия: размер и хеш закреплены в `ModelSpec`; при обновлении менять оба.
Проверка: SHA-256 после скачивания/копирования; на эмуляторе совпал.

## D006 — Локальность и сеть
Статус: принято (2026-10-24).
Решение: инференс только на устройстве; `INTERNET` нужен исключительно для скачивания
весов. Веса, аудио и история диалога в Git не попадают; логи не содержат текстов запросов.
Альтернативы: облачный fallback (отклонено требованиями), предустановка модели в APK
(APK раздувается, обновление весов невозможно).
Проверка: `.gitignore` (`/models/`, `*.gguf*`), отсутствие сетевых вызовов в рантайме чата.

## D007 — Отключение рассуждений
Статус: принято (2026-10-24).
Решение: в Jinja-шаблон передаётся `enable_thinking=false`; дополнительно
`ReasoningFilter` вырезает блок `<think>…</think>` и fullwidth-маркеры, если модель всё же
их выдала. Фильтр переживает разрыв маркера между токенами.
Альтернативы: постобработка только регуляркой по готовому ответу (не работает со стримингом).
Проверка: unit-тесты `ReasoningFilterTest`.

## D008 — Бюджет контекста
Статус: принято (2026-10-24).
Решение: `n_ctx=2048`, резерв 256 токенов на ответ, запас 8; число токенов промпта
считает сам токенизатор модели (`llama_tokenize`). `ContextPlanner` сохраняет системный
промпт и последние ходы, отбрасывая самые старые; если не помещается даже один ход —
запрос отклоняется.
Альтернативы: скользящее окно по символам (неточно для русского и кода), summary истории
(отложено до RAG).
Проверка: `ContextPlannerTest`, отказ при переполнении в `ChatViewModelTest`.

## D009 — История диалога
Статус: принято (2026-10-24).
Решение: один диалог, JSON в `filesDir`; запись атомарная через временный файл.
Незавершённый ответ (`STREAMING` на диске) после падения процесса читается как
`INTERRUPTED` и не отправляется модели повторно.
Альтернативы: Room (избыточно для одного диалога), хранение только в памяти (теряется).
Проверка: `ConversationStoreTest`, ручной перезапуск приложения на эмуляторе.

## D010 — Инструментация и базовая линия проверок
Статус: принято (2026-10-24).
Контекст: для сравнения эмулятора и телефона нужны воспроизводимые замеры без утечки
содержимого диалога в логи.
Решение: нативные логи содержат время загрузки, TTFT, время декодирования, tok/s и RSS;
Kotlin-слой логирует время до первого видимого токена и длительность хода в символах.
Тексты сообщений и промптов не логируются. Замеры — только в debug.
Альтернативы: внешний профайлер (нет на телефоне пользователя), бенчмарк-активити
(отложено, избыточно для M2).
Последствия: логи пригодны для отчёта и сравнения устройств; инструментация остаётся в коде.
Проверка: `docs/IMPLEMENTATION_REPORT.md` §6, §8 (эмулятор x86_64).

## D011 — Отложенные дефекты UI, найденные проверкой
Статус: принято (2026-10-24), исправление не начиналось.
Контекст: прогон M2 на эмуляторе выявил три дефекта, не блокирующих базовый сценарий.
Решение: зафиксировать и не исправлять в рамках проверки: (1) список не всегда
прокручивается к новому ответу; (2) убийство процесса во время генерации теряет показанный
частичный текст (ход становится пустым «Остановлено»); (3) при готовой модели из UI
недоступны «Импорт GGUF» и «Удалить».
Альтернативы: исправить сразу (расширение текущей задачи без согласования).
Последствия: требуют отдельного согласованного блока работ; до этого в отчёте помечены.
Проверка: воспроизведение по шагам в `docs/IMPLEMENTATION_REPORT.md` §7.

## D012 — whisper.cpp в отдельном Gradle-модуле
Статус: принято (2026-10-24).
Контекст: у llama.cpp и whisper.cpp оба CMake-проекта объявляют статические цели `ggml`,
но разных версий (0.27.0 и 0.26.0). В одном CMake-дереве это конфликт целей, а разделять
их переименованием целей — хрупко при обновлении вендоров.
Решение: отдельный Android-модуль `:whisper` (`com.android.library`) со своим
`CMakeLists.txt`, который делает `add_subdirectory(third_party/whisper.cpp)`, собирает
`whisper` и `ggml` статически и линкует в `libwhisper_jni.so` с `-Wl,--exclude-libs,ALL`
и version script `exports.map` (только `Java_com_example_localassistant_asr_WhisperNative_*`).
`:app` зависит через `implementation(project(":whisper"))`.
Альтернативы: одно CMake-дерево с переименованием целей (хрупко), готовый AAR (нет контроля
над ревизией и ABI), динамические бэкенды (не находятся в APK).
Последствия: в APK два нативных `.so`; у каждого ABI `libwhisper_jni.so` 1,41 МБ (arm64) /
1,64 МБ (x86_64); символы ggml/whisper не экспортируются.
Проверка: `llvm-nm -D` и `llvm-readelf -d` по библиотекам из собранного APK — ровно 6
JNI-символов, 0 символов ggml/whisper, `NEEDED` только liblog/libm/libdl/libc.

## D013 — Модель Whisper и проверка артефакта
Статус: принято (2026-10-24).
Решение: многоязычная `ggml-base.bin` (147 951 465 байт, SHA-256 `60ed5bc3…2efe`) из
`ggerganov/whisper.cpp`, ревизия `5359861c…58b1`; язык распознавания задаётся явно (`ru`).
При каждом запуске проверяются размер и ggml-магия; SHA-256 сверяется один раз после
скачивания или импорта. Веса не входят в APK и Git.
Альтернативы: `small`/`large` (заметно больше RAM и медленнее), `base.en` (не подходит для
русского), distil-модели (англоязычные).
Последствия: ~148 МБ в приватном хранилище; на эмуляторе скачивание проверено внутри
приложения, хеш совпал.
Проверка: `WhisperDownloader`/`WhisperModelStore`, инструментальные тесты на фикстуре.

## D014 — Обработка тишины и неречевых меток
Статус: принято (2026-10-24).
Контекст: на тишине whisper выдал `[музыка]` — метку неречевого звука. Такая метка не
должна попадать в поле ввода как готовый текст.
Решение: `AsrTranscript` удаляет аннотации в `[]`, `()` и `*…*`; если после удаления не
осталось ни одной буквы или цифры, результат считается пустым и UI показывает
«Речь не распознана», ничего не вставляя. Аудио хранится только в памяти до распознавания
или удаления.
Альтернативы: только поднять `no_speech_thold` (не гарантирует отсутствие меток), пропускать
метки как есть (пользователь может отправить мусор).
Проверка: `AsrTranscriptTest`, ручной прогон тишины в UI на эмуляторе.

## D015 — Calendar auth: AuthorizationClient без выдуманного срока и с revoke
Статус: принято (2026-10-27), зависимость поднята с разрешения пользователя.
Контекст: Settings показывал «Подключён» по сохранённому токену; disconnect не отзывал
доступ и не сбрасывал GMS-кэш, поэтому повторное подключение ломалось. В
`play-services-auth:21.3.0` отсутствуют `ClearTokenRequest`, `RevokeAccessRequest`,
`clearToken()` и `revokeAccess()` (подтверждено AAR и `javap`).
Решение: `play-services-auth` 21.4.0; состояние связи строится только по результату
`AuthorizationClient.authorize()`; срок жизни токена не изобретается; 401 — единственный
сигнал замены токена; `disconnect` = локальная очистка + `clearToken` + best-effort
`revokeAccess` (для аккаунта и scope `calendar.events`); `connect` всегда заново
`authorize`; late-callback отбрасывается по generation.
Альтернативы: `GoogleSignInClient`/`requestIdToken` (запрещено условием), хранить
`expiresAt` (нельзя проверить без выдумки), не отзывать доступ (оставляет grant).
Аккаунт: Authorization API не возвращает identity, `toGoogleSignInAccount()` в этом
сценарии даёт null. Поэтому при подключении открывается системный `AccountPicker`
(без `GET_ACCOUNTS` и без Google Sign-In), выбранный email сохраняется,
`AuthorizationRequest.setAccount` привязывает consent к аккаунту.
Последствия: при обновлении схемы токен-стора пользователь один раз подключается заново;
`revokeAccess` отзывает все ранее выданные этому приложению scope для аккаунта.
Проверка: `AuthorizedCalendarGatewayTest`; сборка/тесты/lint; сценарий на CPH2581.

## D016 — Calendar read periods и группировка ответа
Статус: принято пользователем и проверено вручную (2026-10-27); pagination расширена D018.
Контекст: недельный ответ повторялся для вопроса об отдельном дне; список был плоским.
Решение: READ/SEARCH используют date как первый день, days как число календарных дней;
при date без days — один день, иначе defaults READ=7/SEARCH=30 с сегодняшнего дня.
Границы — локальная полночь и исключительная полночь следующего за последним дня.
Группировка/сортировка выполняется приложением, all-day date не трактуется как Instant.
Жирные date headings рисуются Compose AnnotatedString без Markdown-зависимости.
Planner выделяет текущий запрос отдельно от reference history, SEARCH сохраняет дату/условие.
Исходное ограничение: одна страница API до 25 событий; pagination/batch впоследствии расширены D018.
Проверка: assembleDebug успешен; пользователь принял группы дат и day-specific поиск на CPH2581.

## D017 — App-side target resolver для одиночных UPDATE/DELETE
Статус: выбран пользователем и проверен на устройстве (2026-10-27); контекст объединён D018.
Контекст: модель не получала реальные event IDs и теряла write intent после SEARCH.
Решение: UPDATE/DELETE содержат поисковые параметры source_date/source_time/query,
приложение хранит последний API result, разрешает target, предлагает выбор при нескольких
совпадениях и запрашивает подтверждение реального события/старых→новых значений.
Неизвестный model ID не является основанием записи. Time-only update сохраняет дату
и длительность; PATCH сохраняет незаданные поля. После подтверждения — refetch/compare.
Отмена/смена подключения очищают pending interaction; контекст не пишется в docs/logs.
Альтернативы: prompt-only (недостаточно); общий bounded LLM/tool loop (отдельный scope).
Проверка: assembleDebug успешно; пользователь принял перенос/удаление, trace PATCH200/DELETE204.
Автотесты этого блока не запускались.

## D018 — Единый Calendar session и общий single/batch pipeline
Статус: архитектура одобрена (2026-10-27); runtime-приёмка реализации НЕ пройдена.
Контекст: предыдущие исправления работали по отдельности, но терялись pending intent/slots
и найденный набор, multiple writes не исполнялись. Требование — расширить одну систему.
Решение: CalendarSession принадлежит executor и единолично хранит draft/targets/selector/
uncertain marker; planner получает typed snapshot, а не воспроизведение user-команд.
new/continue/all — метаданные существующих пяти операций, не дополнительные CalendarOp.
Одиночные и batch UPDATE/DELETE используют один resolve/prepare/confirm/preflight/apply.
CREATE partial slots сохраняются, relative dates фиксируются, topic/Stop/new-chat/account
сбрасывают состояние. Убраны старые sync/runWrite/unused modelContext/result paths.
Пагинация до 500, batch до 100; превышение не выдаётся за полный набор; actual calendar weeks.
Partial outcomes явные, no-op без PATCH, неизвестная запись не повторяется автоматически.
Альтернативы: дополнительные prompt patches/отдельные параллельные caches отклонены;
общий свободный LLM/tool loop не нужен для этих детерминированных действий.
Последствия: in-memory session без process-death recovery, batch не транзакция;
классификация продолжения всё ещё зависит от локальной модели.
Проверка: assembleDebug и diff check успешны; unit/UI tests не запускались по запрету;
Device validation выявила неверные DELETE/new на поисковые реплики, потерю CREATE title
и сброс draft на slot-ответе. Выбор op/context всё ещё слишком доверен модели;
предложена app-owned turn/slot policy, её реализация ожидает согласования.
Схема текущего кода: CALENDAR_FLOW.md; её наличие не означает приёмку сценариев.

## D019 — App-owned намерение и версионированный field-patch
Статус: одобрено пользователем и реализовано (2026-10-27); ручная приёмка ещё впереди.
Контекст: D018 не прошёл приёмку: модель путала поиск/DELETE, new/continue, title и slot reply.
Решение: CalendarSession единственный state owner (taskId/version/phase, object/op, awaiting,
targets, draft и object без op). Stateless CalendarTurnPolicy определяет допустимую op/
переход из реплики и snapshot; новая задача не наследует старый write intent.
Модель сначала получает явный snapshot с null/[], затем предлагает только set/clear с
task_id/version. Tool/context/all/ID из модели больше не являются источником действий.
App извлекает типовые русские поля и проверяет grounding предложений; неверный patch
не отменяет достоверные app поля и не меняет операцию. Неизвестное уточняется, не выдумывается.
Связанный объект без операции сохраняется до выбора действия; stale версии не применяются.
Пагинация/подтверждение/PATCH/no-op/formatting остаются общей цепочкой. Поиск — bounded
полный период и local AND tokens с ё/е и простыми окончаниями вместо потери словоформ в q.
Границы: bounded Russian grammar, не универсальная NLP/морфология; in-memory process state.
Проверка: assembleDebug/diff check успешно; unit/UI tests не запускались по запрету;
device install/capture текущей policy не выполнялись. Старый D018 runtime не считать успешным.

## D020 — Общий temporal parser и явные calendar/rolling bounds
Статус: запрошено пользователем и реализовано (2026-10-27); ручная приёмка впереди.
Контекст: «на этой неделе» не распознавалось из-за отсутствующего окончания «е»;
пользователь дополнительно запросил относительные дни и прошлые/будущие недели/месяцы.
Решение: одна грамматика CalendarTemporalExpression для полного span периода и follow-up;
CalendarTime один вычисляет границы. Relative days ±2, calendar current/previous/next,
rolling LAST/UPCOMING, quantities и ago/through как point-date.
Calendar weeks пн–вс, month lengths настоящие, end exclusive. Rolling LAST — назад от
следующей полуночи с включением сегодня, UPCOMING — с сегодняшней полуночи.
Фразы полностью исключаются из query. Неясная quantity уточняется в той же задаче,
reference anchor сохраняет исходную дату при задержке ответа. Limit90 дней остаётся явным.
Добавлены focused regression source tests без запуска по запрету пользователя.
Зависимости, auth, permissions, native и согласованные правила подтверждений не менялись.

## D021 — Rename roles, weekday/date intent и явная граница Google Tasks
Статус: Event-исправления реализованы по запросу (2026-10-27), runtime-приёмка впереди;
подключение настоящего Google Tasks API ожидает отдельного согласия.
Контекст: greedy rename терял target query и включал source/date в новое title; weekday
отсутствовал, absolute date не участвовала в раннем intent; склонения имён не совпадали.
Решение: отдельный stateless rename parser перед общим command/date parsing; literal new
title, source query/date, title-only matching и explicit ambiguous clarification.
Shared temporal parser включает weekday/absolute dates, текущая неделя по умолчанию,
previous/next week; contradictory date требует уточнения. Case endings расширены без fuzzy.
Tasks не существуют в Events API: запросы Tasks теперь guard-ятся вместо false mixed list.
OAuth/new API не добавлены. Для будущей интеграции нужен отдельный source adapter в той же
Session, typed target refs и новый scope. Tasks.due — date only, scheduled time не доступен.
Проверка: assembleDebug/diff check успешны; focused source tests добавлены, не запускались
по запрету; capture содержал только один успешный CURRENT_WEEK read, остальное не доказано.

## D022 — Публичный Google Tasks API в общей Event/Task-цепочке
Статус: пользователь выбрал вариант 1, одобрил Tasks API/scope и сообщил о ручной настройке;
реализовано 2026-10-28, runtime OAuth/API-приёмка ещё впереди.
Решение: tasks scope вместе с calendar.events, одна авторизация аккаунта/проверка grants,
общие HTTP transport и read-only retry policy. Отдельный native Tasks adapter, тот же executor/
Session/confirmation, kind и list-qualified refs не допускают смешения IDs/операций.
Модель не выбирает API и не создаёт refs. Task fields title/notes/optional due/status,
list catalog/choice в Session. due date-only без timezone shift, нет fake all-day/time/Event.
Task create не требует часов; unsupported clock сохраняет draft для «без времени».
Read all-dates включает undated, status/date/list filters задаются явно; writes подтверждаются.
Ограничения: catalog100/result500/batch100/range90, без silent truncation; batch не atomic.
List CRUD/move, subtask creation, recurrence/time не добавлены. Parent deletion не делает
скрытый cascade, assigned deletion отражает эффект на исходную задачу в confirmation.
Альтернативы Task+local time или companion Event не выбраны; старые Events не мигрируются.
Проверка: assembleDebug/diff check успешно; source tests добавлены, unit/UI/test compilation
не запускались по прежнему запрету. Девайс не инспектировался, новая сборка не установлена.
Ручные шаги Google Console/consent и проверка — GOOGLE_TASKS.md.

## D023 — Новый запрос по умолчанию Event, source binding только для продолжений
Статус: явно задано пользователем, реализовано и принято им на OnePlus 2026-10-28.
Контекст: Task lifecycle/search принят, но новое named question наследовало TASK.
Capture пуст; buffer dump отменён из-за отсутствия девайса. Причина подтверждается
source fallback в коде и Task header из сообщения пользователя, не придуманными HTTP-логами.
Решение: new standalone subject без Task marker = EVENT, явная «задача» = TASK.
Source inheritance ограничено refs/slots/field edits/temporal reply; possessive внутри
нового имени не reference. Новые Event queries не наследуют Task list/status filters.
Empty read может продолжать query; write требует реальные refs. TITLE data не меняет kind.
Проверка: assembleDebug/diff check успешны; CalendarSourceSelectionTest authored not run.
Сборка установлена install -r, последний ручной набор принят пользователем; это не полный
автоматический regression PASS. Scopes этой правкой не менялись, data не мигрировались.

## D024 — Лобби и границы первой библиотеки документов (2026-11-02)

Статус: одобрено пользователем; UI собран, установлен и принят пользователем.
Решение: перед текущим чатом добавить лобби с двумя режимами «Чат»/«Хранилище».
Первый блок — навигация и пустая библиотека; затем отдельно импорт и поиск.
DOCX/XLSX, десятки документов/сотни строк. Начальный embedding-кандидат —
multilingual-e5-small, runtime/артефакт предстоит проверить. PDF/OCR отложены.
Расчёты XLSX выполняет приложение по проверенным параметрам, не арифметика LLM.
UI использует существующий Compose без новых зависимостей; Activity-scoped ViewModel
сохраняют чат, rememberSaveable/SaveableStateHolder — экран и локальное UI state.
При выходе из чата — stop generation/hold recording/cancel transcription.
Проверка: assembleDebug/diff check PASS; после переустановки актуального APK пользователь
подтвердил «теперь правки работают». Это не автоматическая регрессия всех lifecycle-сценариев.

## D025 — Локальный OOXML import и структурированный preview (2026-11-02)

Статус: реализация одобрена; сборка и targeted parser tests PASS, device-приёмка впереди.
Решение: DOCX/XLSX читать встроенными ZIP/SAX API без дополнительных библиотек;
SAF OpenMultipleDocuments, приватная копия оригинала, SHA-256 dedup, per-document
AtomicFile metadata/content JSON. Для согласованного малого архива Room пока не нужен.
Альтернатива: общий тяжёлый Office SDK — больше зависимостей ради ограниченного извлечения.
DOCX: основной текст/таблицы в порядке, картинки/объекты игнорируются; без колонтитулов,
комментариев/сносок и воспроизведения полной вёрстки/автонумерации.
XLSX: листы/строки/координаты/типы/raw values/формулы/кэш; стиль используется для даты,
система1900/1904 сохраняется. Полного format engine и formula evaluation нет; shared
formulas без текста обозначены, не реконструируются. Скрытые листы/строки включены,
merged cells представлены исходным значением. Preview — листы и страницы по 50 блоков.
Ошибки по файлам, прерывание процесса → ERROR и повторный импорт. Лимиты: файл50МБ,
XML64МБ/text4млн/100тыс непустых или формульных ячеек; пустое оформление не учитывается.
Запрет DTD/внешних сущностей, media/embedded не открываются; XML byte budget ограничен.
Примеры не в Git: /doc_examples/. Их текст/значения не логируются и не сохраняются в docs.
Проверка: assembleDebug + только OfficeDocumentExtractorTest разрешены отдельно: PASS,
6 tests включая 4 примера, без skips. Исправлен FAIL на 283180 XML cell records за счёт
подсчёта лишь непустых/формульных ячеек. Lint/остальные tests/device не запускались.
Следующий шаг: ручной импорт и проверка persistence, затем отдельно embeddings/RAG/аналитика.

## D026 — Кандидат embedding artifact и проверка существующего runtime (2026-11-02)

Статус: скачивание и host-проверка разрешены; интеграция ожидает проверки reference parity.
Для multilingual-e5-small предлагается сначала GGUF Q8_0 через существующий llama.cpp,
без новых Gradle dependencies и обновления vendored runtime. Кандидат/хеш/источники:
EMBEDDING_RESEARCH.md. Он сторонний, не официальный GGUF intfloat; заявления автора
конверсии не считать результатами проекта. Подтвердить tokenizer/mean/L2/384 dims/русский
retrieval до индексации. Альтернатива при несовместимости — иной runtime, только после
отдельного выбора и согласования. Веса скачаны вне Git, hash/size PASS; native host smoke PASS
(384 finite/unit norm,3 Russian top1). App/vendor не менялись; device не проверялся.
GGUF context511 и MASK ID→заглушка требуют reference/boundary checks. Следующая остановка —
host-only tokenizers dependency и официальный tokenizer.json разрешены и получены.
Reference parity16/24 FAIL: trailing whitespace и literal special markers требуют
одинаковой input policy. Остановка перед JNI; не считать весовую конверсию ошибочной
только по различию parse_special=false и reference AddedTokens. После согласованной подготовки
search input normalized parity24/24 PASS. Новый стоп: actual position tensor511 и XLMRoberta
converter offset при настоящем BertModel; требуется сравнение с official positional weights.
HTTP Range отдельно разрешён/выполнен:808896 bytes, побайтово подтверждено удаление строки0
(GGUF == official rows1..511 целиком). Metadata-only fix не применять.
Предложено, пока НЕ принято: сохранить исходный файл и создать corrected copy с official
positional tensor512x384/context512, byte-identical остальными tensors; проверить новый hash,
integrity/tokenization/retrieval/boundary. Это меньше deps/downloads, чем полная конверсия.
Подробности — research doc. До решения JNI-интеграция остановлена.

## D027 — Локальный posfix артефакт вместо сдвинутого стороннего GGUF (2026-11-02)

Статус: исправление и host-проверки одобрены и выполнены; Android-интеграция впереди.
Причина: Range comparison подтвердил, что сторонний GGUF хранит official positional rows1..511
вместо0..511. Metadata-only change не исправляет весовой сдвиг.
Решение: сохранить исходный artifact и создать отдельную reproducible copy через
tools/embedding/repair_e5_positions.py, full official F32 positional table512x384/context512,
пересчитать descriptors/offsets. Остальные196 tensors и tokenizer metadata не менять.
Inputs строго pinned hashes, existing outputs не перезаписывать. Output SHA256:
bdb785b2ca8532ff3097f93ac3e4e1d560a122cfba377bf559895aebfd9be6d9; size132440544.
Альтернатива — полная конверсия official checkpoint с дополнительными downloads/deps.
Проверки: integrity PASS, prepared tokenizer24/24, Russian retrieval3/3, repeated inputs,
inference510/511/512 tokens и513 preflight rejection PASS на host pinned llama.cpp.
Границы: не полная numerical parity/не device-проверка; на Android guard ещё не реализован.
Все веса/fixtures/reports остаются в ignored models; app/vendor/версии не менялись.

## D028 — Отдельный embedding runtime в существующей native библиотеке (2026-11-02)

Статус: реализация и focused проверки одобрены и выполнены; device integration впереди.
Решение: EmbeddingContext отделён от Qwen handle, current pinned llama.cpp/общий init once,
без новой зависимости/обновлений runtime. MEAN/noncausal384, L2, total token budget512.
JNI принимает standard UTF8 ByteArray, не Modified UTF8; registry shared_ptr + mutex/atomic
защищает lifetime и cancel/destroy. Native core не зависит от Android/JNI и проверяется на host.
Kotlin E5EmbeddingEngine принимает только pinned posfix hash/size, готовит query/passage input,
выполняет всё на одном worker, сериализует Mutex. CancellableContinuation сигналит native,
но finished.await в NonCancellable удерживает operation ownership до завершения работы.
Aborted context выгружается; повторное использование требует load. Close идемпотентный,
не освобождает общий process backend и не затрагивает Qwen. Состояния через StateFlow.
Альтернатива: переиспользовать Qwen context — конфликт lifecycle/моделей; не выбрана.
Проверки: assembleDebug arm64-v8a/x86_64 PASS,6 E5EmbeddingEngineTest PASS (fake bridge),
production native-core host probe PASS (ranking,384/unit,repeat,boundaries,cancel/reload).
Android JNI runtime пока не вызывался. Engine не создан в UI/AppContainer, import весов,
index/search и device ресурсы — следующие блоки. Новых dependencies/permissions нет.

## D029 — Малый локальный документный индекс и ограниченная XLSX-аналитика (2026-11-02)

Статус: все оставшиеся этапы разрешены, код добавлен; верификация заблокирована реальным
subtotal Unicode-case bug (15/16 tests PASS), до исправления не считать готовым.
Решение: per-doc AtomicFile binary vector snapshots, модель/формат версии pinned, exact scan
для малого архива и BM25+cosine RRF. App-scoped embedding import/lifecycle в существующем
AppContainer, private files, никакого cloud inference/new dependency или upgrades.
Qwen — отдельные bounded messages без Calendar tools, checked source IDs/not-found;
XLSX — allowlisted JSON plan, validated columns/ranges/filters, BigDecimal в приложении,
unique-key comparison, formula cached results только по явному UI setting.
Alternative DB/vector runtime отложены до доказанной потребности объёма; arbitrary SQL/code
не исполняются. Индексация atomic/cancellable, valid index reuse; archive cap25k chunks.
Проверки: compile PASS, parser6/6 с4fixtures и pipeline9/10. Ошибка «Итого» не скрывается;
исправление/повторная команда затем разрешены пользователем. Только regex flag заменён на
RegexOption.IGNORE_CASE (Unicode), без изменения теста. Финальный assembleDebug/16 targeted
tests/lintDebug PASS19с. Device/real Qwen ещё не проверены, APK и веса не устанавливались.

## D030 — Встроенный posfix GGUF и подготовка приватной копии (2026-11-02)

Статус: включение весов в APK, fix и проверки явно одобрены; build/tests/lint/ZIP PASS,
Android extraction/reuse ещё не проверены. Подробности: BUNDLED_EMBEDDING.md.
Решение: генерируемый asset из ignored models/ с pinned hash/size при сборке,
не хранить binary duplicate в src/main/assets/Git; MIT notice/provenance в APK.
App background prepare → verified same-directory atomic replacement; существующий valid
target переиспользовать; не загружать RAM/native model при простом извлечении asset.
Альтернатива ручного импорта теперь fallback, не основной путь.
Первый configure FAIL: SourceSets Provider запрещён. Предложен поддерживаемый Variant
Sources API, без upgrades/новых dependencies или отключения AGP проверки.
Fix затем разрешён/внесён: Variant Sources API generated source directory с task provider,
без upgrades/gradle.properties flags. assembleDebug/4 tests/lintDebug PASS1m5s.
APK172267453 bytes; model132440544/SHA256 и NOTICE в actual ZIP проверены PASS.
Установка bundled APK не выполнялась; не выдавать host tests за device приёмку.

## D031 — Автоматический документный маршрут и подробный многочастный RAG (2026-11-02)

Статус: запрос пользователя реализован, сборка/54targeted tests/lint PASS; device ещё впереди.
Пользователь отклонил UI переключатель: context-aware document routing в любой момент чата.
Решение: validated JSON route по current question/recent6 turns/doc flags, reformulated
standalone query для followup. Other → прежний chat/calendar; doc branch не меняет pending
CalendarSession и не использует calendar tools. Качество семантического выбора проверить вручную.
Для подробности — все search hits, exact-budget batches/source splitting и до1024 output tokens
на порцию. Полные original fragments добавлены отдельным app-labelled разделом того же ответа,
чтобы генеративное резюме не скрывало детали. Это top-ranked search, не весь архив exhaustive.
Source IDs валидируются; сбой одной порции обозначается с raw evidence, не fake answer.
Persist v2 documentRelated/source metadata, raw markers не strip у doc replies; original links
в bubble, requestId/cancel guard; ordinary prompt не получает raw retrieved messages.
Alternatives rejected: UI document toggle (пользователь), единственный увеличенный prompt
(не вмещает все sources в2048), только длинный summary (не гарантирует сохранение деталей).
Проверки: assembleDebug/54 targeted tests/lintDebug PASS44с; не device/не real semantic accuracy.

## D032 — Explicit doc Switch и short→full по snapshot вместо auto-route (2026-11-02)

Статус: новый UX явно одобрен, код добавлен, проверки заблокированы; заменяет D031.
Пользователь сообщил calendar/doc misrouting и technical output. Решение: OFF(default)
исключает DocumentChatGateway из send/newChat idle; ON строго documents без LLM router.
Initial short processed384; Full action только completed primary short приON, новое сообщение,
immutable evidence/question snapshotpersistv3, iterative synthesis без appended raw chunks.
Ошибка full остаётся ошибкой, technical evidence не заменяет Qwen. Sources — отдельные links.
Calendar punct trim раньше cleanup устраняет возможный QUERY «в календаре» в вопросах с ?.
Run assembleDebug/9suites/lint FAIL:95tests/39fail (38calendar Regex portability на JDK25
предполагается;1wrong exception expectation при корректном cite rejection). До дальнейших
fixes остановились по инструкции пользователя. Не считать APK проверенным/device-ready.
Следующий блок — явные Unicode boundaries/test expectation после согласия. См. CHAT_DOCUMENT_MODE.md.

## D033 — Переносимые Calendar Unicode boundaries и qualifiers всех weekdays (2026-11-02)

Статус: fixes/расширенные tests разрешены; assembleDebug/308tests/lintDebug PASS34с.
Java25 probe подтвердил ASCII default\b для русских слов; менять JDK/flags значило бы скрыть
проблему. CalendarRegex заменяет controlled pattern\b на explicit Unicode letter/mark/number/_
boundary; применяется в3 parser files без изменения capture groups/date arithmetic.
Qualifiers consumed для7 дней и падежей: current/previous/next calendar week сMonday start;
contradictions дают clarification. Punctuation trim до suffix removal устраняет QUERY «в календаре».
Проверки:70data scenarios×3=210 +boundary3 +прежние95, все308 PASS. Citation test expectation
исправлено точно, validator не ослаблен. Versions/API/account/permissions не менялись.
Устройство/Google API не использовались; Android execution/real Qwen ещё проверить вручную.
Checkpoint: CHAT_DOCUMENT_MODE.md.

## D034 — READ не должен искать служебное «у меня» в названиях событий (2026-11-03)

Статус: минимальное исправление и проверки разрешены,326calendar tests/build/lint PASS42с.
Exact before-fix test подтвердил QUERY="у меня" у date-only possessive question: дата верна,
но app local filter отбрасывает события, не содержащие этих слов.
Решение: после существующей очистки, только для READ, пустить whole-service-only остаток
в empty content; не снимать реальные title filters. Quoted title «У меня» сохранён/проверен.
Не менять API/аккаунт/range/permissions или другие системы. 70relative scenarios теперь
имеют4 checks=280, DateRead7 и прочие suites дают326PASS. Device/live events не использовались;
APK не установлен. Следующая приёмка — точный запрос на устройстве по разрешению пользователя.

## D035 — READ tense framing не является title query (2026-11-03)

Статус: minimal Calendar fix разрешён, build/399targeted calendar tests/lint PASS19с.
Продолжение D034: date-only question с «были» сохраняла слово как query; правильная дата
не спасает от local filter. Whole-service-only READ grammar дополнена были/было/был/будут/
будет/есть, без удаления настоящих query words. Quoted titles сэтими словами сохраняются.
Exact past Saturday и synthetic returned event проверены; relative matrix350+остальные49.
Другие задачи отложены. APK не установлен, live Calendar/аккаунт/device не использовались.

## D036 — Calendar success только из исполнителя, исходный read-set отдельно (2026-11-03)

Статус: user demanded no calendar simulation; code и453 targeted tests/build/lint PASS.
Subject-first supported time changes распознаются вне quoted data, неразобранные writes
получают app clarification. Dispatcher backstop и исключение Calendar reports из ordinary
history убирают путь имитации success формата; system prompt не основной механизм.
Session original resultSet/resultSelector отделены от active selection. App-owned bool
useResultSet позволяет named UPDATE в том же original day; only successful provider writes
меняют cached read set. Confirm/проверка stale event/API response до UI результата обязательны.
Alternative простой prompt-only запрет недостаточен; переиспользовать один narrowed set
недостаточно для второй записи. Version4 optional metadata обратно совместимы.
Проверки: separate confirmations, API writes/IDs, same-name other day, cancel/retry/error/
no-op, clarification, quoted data, Tasks/chat/history/mode regressions. Финал453PASS7с.
Screenshots ignored/private, данные не в docs/logs. APK не установлен, device QA впереди.

## D037 — Уточнение сохраняет active move, destination и clock отдельны от duration (2026-11-03)

Статус: user одобрил реализацию/проверки, assembleDebug/460targeted tests/lintDebug PASS28с.
Решение: CalendarTurnPlan.clarificationFields app-owned, полноценный UPDATE проходит
target resolution+draft staging до question; pending slots не живут только в тексте Qwen.
Absolute hour phrase0..23 без later/earlier → clock, explicit duration → duration,
неоднозначный relative offset → awaited date/time. Invalid bare reply сохраняет awaited slot.
GENERAL remark не отменяет Calendar action; new Calendar op/CANCEL/newChat/account reset
явно заменяют его. Session в памяти runtime, без обещаний disk/process-death восстановления.
Нельзя повторно искать moved known ID по старой дате: local trusted result match+fresh GET
и pre-write validation вместо потери цели. Confirmation/API-success обязательны.
Date-only сохраняет actual old time/duration; date+time поддерживает weekday/named/numeric.
Альтернатива раннему CLARIFY без draft отклонена как причина потери действия; угадывание
relative shift/длительности отклонено. Полный synthetic workflow460PASS, device QA впереди.

## D038 — Named DELETE использует original read-set до явной смены source date (2026-11-03)

Статус: user разрешил проверку/fix,assembleDebug/468targeted tests/lintDebug PASS9с.
Исходный useResultSet былUPDATE-only; named DELETE послеREAD терялконтекстдня. Общее
scopedDelete rule безliteral names удерживает original set/day, включая retry послеclarify.
Explicit sourceDate может заменитьscope; пустой set послеdelete вызываетclarification,
неdefault lookup будущего namesake. Selection,provider refetch/stale check,confirmation и
API-success report не обходятся. Тесты8 synthetic scenarios плюс460 прежних PASS.
Alternative fallback кdefault range отклонён как риск выборадругой одноимённойзаписи.
Live Calendar не читался; APK установлен на CPH2581 с сохранением данных по прямому запросу.
Пользователь разрешил отдельный коммит исправления относительно f21873d; ручная приёмка впереди.

## Формат нового решения
ID и дата; статус (предложено/принято/заменено); контекст; решение; альтернативы; последствия; способ проверки.
Не удалять старые решения: помечать заменёнными с указанием нового ID.
