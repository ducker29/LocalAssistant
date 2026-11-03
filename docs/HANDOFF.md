# Передача контекста проекта

## Checkpoint перед общим коммитом (2026-11-03)

Пользователь явно запросил общий commit накопленных изменений. Scope: все безопасные
исходники/tests/docs/build configuration/tools, включая его AGENTS/prompt/review.
Локальный calendar-auth.log исключён; ignored weights/examples/screenshots/APK/secrets не stage.
Не удалять log/пользовательские файлы и не push. Последняя verification460tests/build/lint PASS,
APK установлен на CPH2581; pending/time/date fixes всё ещё требуют пользовательской приёмки.
Следующий рабочий шаг после фиксации — запросить конкретную новую задачу/результат ручной проверки.

## АКТУАЛЬНО — pending action и междневной перенос PASS, 2026-11-03

Последний user scope: screen3 time phrase +persistent current action +destination day/date/time.
Ранний CLARIFY от duration UPDATE заменён app plan.clarificationFields: target/draft/awaiting
staged executor до уточнения; bare reply/invalid retry сохраняют operation/ID/missing slot.
Absolute hour0..23 без relative marker считается clock; explicit duration не clock;
relative offset не угадывается. GENERAL remark больше не clear Calendar context. New Calendar
action/cancel/newChat/account resets replace pending; это не process-death persistence.
Known moved IDs фильтруются по trusted resultSet и GET валидируются, не теряются из-за
повторного поиска old source-day. Confirmation/stale recheck/provider-success report сохранены.
CalendarMoveContinuationTest7 +12 прежних suites:460tests PASS; assembleDebug/lintDebug PASS28с.
APK app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу на CPH2581:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
Next — manual absolute time/relative clarification→bare time, new date+time/date-only и moved-ID followup.
Личные screenshot данные/Google/device не использовались. См. CALENDAR_SEQUENTIAL_EDITS.md.

## АКТУАЛЬНЫЙ РЕЗУЛЬТАТ — two-event edits и no simulation, 2026-11-03

По локальным screenshots диагностирован subject-first command miss→GENERAL→imitated
report. Личные titles/content в fixtures/docs/logs не переносить; screenshots/ ignored.
CalendarTurnPolicy: bounded subject-first time parser, conjunction, ambiguous write→CLARIFY.
Session original resultSet/selector отдельно от active targets; known named update получает
app-owned useResultSet, original day сохраняется. API success обновляет cached original set,
confirm→validate→write путь остаётся единственным. No-op/cancel/failure честные результаты.
Chat backstop/metadata v4 calendarRelated/legacy report prompt exclusion не дают брать
success report как ordinary model continuation; prompt rule лишь дополнительный слой.
См. CALENDAR_SEQUENTIAL_EDITS.md. assembleDebug+12suites+lintDebug PASS7с,453tests.
Предварительные3/1fail были ошибками synthetic RFC3339/timezone fixtures; исправлены,
assertions подтверждения/реальной записи не ослаблялись. Google/API/device не использовались.
APK app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу на CPH2581:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
Следующий шаг — ручной two-event flow. Старые messages не переписывались; проверять новые результаты.

## АКТУАЛЬНО — past-tense Calendar listing fix, 2026-11-03

Пользователь принял possessive fix, затем сообщил past Saturday false-empty с правильной
датой. READ whole-service cleanup не знал «были»; дополнен всеми common copula forms
были/было/был/будут/будет/есть. Только whole residue, не removal реальных title words.
Exact/date/gateway-returned-event и quoted-name guard tests добавлены; relative matrix350.
Разрешённый assembleDebug+6calendar suites+lintDebug PASS19с,399tests/0failed/errors/skips.
APK app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу на CPH2581:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
Следующий шаг — manual «какие события были в прошлую субботу?» сdoc SwitchOFF.
Другие системы/API/account/device не менялись/не использовались.

## АКТУАЛЬНЫЙ РЕЗУЛЬТАТ — Calendar possessive query fix, 2026-11-03

User scope: только Calendar false-empty для «какие события у меня сегодня?».
Expected-red exact test по согласованию подтвердил actual query="у меня" до fix.
CalendarTurnPolicy: READ-only removal whole service-only remainder («у меня»/«в календаре»),
не игнорирование всех filters; quoted «У меня» сохраняется как real title query.
DateReadRegressionTest теперь7tests (exact variants/executor returned event/quote guard);
RelativeDateQueriesTest280checks (70×4, добавлен possessive READ).
assembleDebug +6calendar targeted classes+lintDebug PASS42с,326tests/0failed/errors/skips.
Первый1test FAIL намеренный before-fix repro, не блокер/не зависание.
APK app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу на CPH2581:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
Google Calendar не читался. Следующий шаг — manual exact question сdoc SwitchOFF.
Не расширять scope на другие жалобы пользователя. API/account/versions/permissions не менялись.

## АКТУАЛЬНЫЙ РЕЗУЛЬТАТ — explicit mode/Calendar fixes PASS (2026-11-02)

«Доделывай и запускай тесты» выполнено. CalendarRegex explicit Unicode boundaries в
TurnPolicy/TemporalExpression/RenameExpression; weekday qualifiers для7 дней/падежей,
конфликт → clarification. Punctuation fix подтверждён date READ tests. RAG test ловит
точный IllegalStateException+message, validator не менялся. Java25 probe подтвердил default\b.
Никаких updates JDK/Gradle/flags. assembleDebug+11targeted suites+lintDebug PASS34с,
308tests/0failed/errors/skips (210 relative,3 boundary,95 прежних). APK сbundled model
app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу на CPH2581:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
Следующий шаг — manual OFF→calendar,ON→short→Full/source links/restart и real Qwen/Google приёмка.
История предыдущегоFAIL ниже не является актуальным результатом. См. CHAT_DOCUMENT_MODE.md.

Последнее действие по запросу: добавлен CalendarRelativeDateQueriesTest,70 parameterized
scenarios x3=210 checks; пока не запускался. Five relative days, this/previous/next week,
all7 weekdays с this/previous/next adjectives и падежами, unqualified weekdays/year/DST.
Проверяет recognizer consumption/bounds и full READ/quoted SEARCH no query pollution.
Новый class надо включить в следующее разрешение запуска; production parsing этим шагом
не менялся. Нынешний weekday regex не учитывает preceding adjectives: исправить по требованиям,
не подгонять expectations. Предыдущий FAIL95/39 остаётся актуальным.

Пользователь теперь явно выбрал Switch OFF/ON; это заменяет предыдущий auto-route.
Добавлен зелёный iOS-подобный Switch вместо hint. OFF не вызывает doc gateway/index,
ON отправляет запрос только в документы. Short processed answer384, рядом сCopy Full button
только у completed primary short сsnapshot иON. Full отдельным сообщением, original snapshot,
iterative processed output, no raw evidence. Snapshot persists v3, full link/no recursive button.
См. CHAT_DOCUMENT_MODE.md для UX/изменённых компонентов/точек проверки.
Storage answer тожеshort; technical cards остаются только по explicit «Найти».

Разрешены assembleDebug/9targeted suites/lintDebug. FAIL38с: APK package PASS;95tests/39fail.
38calendarfail (даже find завтра/commands → null) указывают на default Java25 `\b` без Unicode
word classes; java25.0.3 подтверждён. Требуется переносимое boundary исправление, не downgrade
или отключение тестов. One production punctuation trim уже внесён для лишнего QUERY «в календаре».
1RAG failure — test catchesIllegalArgumentException, actual unknown ID IllegalStateException;
validation работает. Mode/history/chat/pipeline и остальные4RAG tests PASS. Lint финального PASS нет.
По просьбе пользователя на проблемах ОСТАНОВЛЕНО. Никакие следующие fixes ещё не внесены.
Нужен ответ на Unicode-boundary diagnosis/fix + корректное exception expectation и повтор
той же команды. APK не установлен; device/Google API/личные события не трогались.

## Предыдущий UX (заменён explicit mode) — автоматический RAG в чате, 2026-11-02

Пользователь попросил полный ответ по найденному и доступ из основного чата, затем явно
отверг переключатель. НИКАКОГО режима/переключателя в UI не добавлено.
Новый DocumentChatGateway/IndexedDocumentChat автоматически классифицирует question с6
recent turns/doc flags через bounded validated JSON; other оставляет старый chat/calendar.
AppContainer/MainActivity/ChatViewModel wired; document branch до calendar prepareTurn,
не изменяет calendar session. Отмена/новый чат защищены requestId, источники в bubbles.
DocumentLlm: все search hits, budgeted split/batches, output1024 вместо384, подробный ответ
и полные raw fragments в том же сообщении; invalid per-batch JSON → явная warning+raw data.
ChatMessage/ConversationStore v2 сохраняют source metadata/doc flag; старые записи совместимы,
raw markers не очищаются у doc replies. Raw document turns не идут в ordinary chat prompts.

Разрешены assembleDebug и5targeted classes+lintDebug: BUILD SUCCESSFUL44с,54tests PASS
(Detailed4/Automatic5/ChatVM31/Store4/Pipeline10),0 failures/errors/skips.
APK app/build/outputs/apk/debug/app-debug.apk со встроенным posfix установлен по прямому запросу:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, CPH2581,
данные сохранены. Device/real Qwen auto-route и полнота релевантности ещё не приняты.
Следующий шаг — «какая гарантия...», «а подробнее?», смена темы, calendar followup и restart/source links.
Calendar tests не запускались; обычный ChatVM regression PASS. Версии/permissions/deps без изменений.

## Предыдущий результат — bundled APK собран и проверен, 2026-11-02

Пользователь разрешил встроенную posfix модель. Добавлены PrepareBundledEmbedding Gradle
task с size/hash guard, BundledE5Model atomic IO helper, app AssetManager wiring/prepare UI,
MIT/provenance NOTICE и4focused tests. Веса берутся из ignored models/, binary в Git не добавлен.
Разрешены assembleDebug+BundledE5ModelTest+lintDebug и проверка asset внутри APK.
Первый запуск FAIL7с на configure: SourceSet assets.srcDir не принимает Provider в текущем AGP.
После остановки пользователь разрешил fix/повтор: androidComponents.onVariants →
variant.sources.assets.addGeneratedSourceDirectory с PrepareBundledEmbedding::assetDirectory.
Versions/gradle.properties не менялись, preBuild ручной dependency удалён как ненужный.
Повтор команды PASS1m5s: assembleDebug,4 BundledE5ModelTest,lintDebug. ZIP entry actual
size/hash и NOTICE PASS. APK172267453 bytes (164.29MiB), модель132440544 bytes.
Свежий bundled APK затем установлен по прямому запросу пользователя на CPH2581:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success,
без очистки данных. Следующий шаг — ручная первая подготовка/reuse и индексация на Android.
Подробнее BUNDLED_EMBEDDING.md. Отдельный перенос модели теперь не нужен.

## Предыдущий результат — документный APK и проверки PASS, 2026-11-02

Все оставшиеся функции добавлены в код по разрешению пользователя. DOCUMENT_SEARCH.md
описывает source/index/model import/hybrid/RAG/typed XLSX arithmetic/UI и ограничения.
Отдельно разрешены assembleDebug, только DocumentPipelineTest+OfficeDocumentExtractorTest,
lintDebug. Общий запуск FAIL50с; code compiled,15/16 tests passed, нет свежего assemble/lint PASS.
Один реальный bug: SpreadsheetCalculator.totalLabel использует inline (?i), uppercase
кириллица «Итого» не совпадает, итог может посчитаться дважды. Тест subtotal guard корректен.
После остановки пользователь разрешил минимальный fix/повтор: заменён только regex flag
на RegexOption.IGNORE_CASE (Unicode), тест не менялся. Повтор согласованной команды —
BUILD SUCCESSFUL19с: assembleDebug,16 целевых tests и lintDebug PASS.
Свежий APK app/build/outputs/apk/debug/app-debug.apk затем установлен по прямому запросу:
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, CPH2581,
без очистки данных. Embedding weights не переносились. Следующий шаг — ручная проверка;
модель models/multilingual-e5-small-q8_0-posfix.gguf нужно перенести и импортировать в UI.
Содержимое документов/веса приватные, app files не копировать в docs/logs. Device не трогали.
Qwen реальный structured plan/search quality, Android index AtomicFile/SAF и ресурсные
замеры ещё не проверены; остаётся ручная приёмка после исправления и нового build PASS.

## Предыдущий блок — runtime реализован и собран (2026-11-02)

Новые production files: cpp/embedding_runtime.{h,cpp},cpp/embedding_jni.cpp,
embedding/{EmbeddingEngine,EmbeddingNative,VerifiedE5Model,E5EmbeddingEngine}.kt.
CMake подключает cpp, llama_jni.cpp экспортирует wrapper общего backend_init_once.
Независимый context MEAN/noncausal384/512, pinned posfix SHA,size; UTF8 bytes вместо
GetStringUTF. Один Kotlin worker/Mutex/StateFlow; explicit cancel и coroutine cancel
доходят до native atomic flag/abort callback. nativeOperation ждёт finished в NonCancellable
до освобождения Mutex; aborted context выгружается. registry shared_ptr защищает cancel/destroy.
Load/unload/close сериализованы, close idempotent; библиотека впервые грузится на worker.

По отдельному разрешению выполнены assembleDebug + только E5EmbeddingEngineTest:
BUILD SUCCESSFUL1m1s,6 tests/0 failures/errors/skips, native arm64/x86_64 compile PASS.
Host target embedding_runtime_probe (tools/embedding/embedding_runtime_probe.cpp + настоящий
production core) PASS ranking/384/unit/repeat/boundaries/cancel/unload/reload.
JNI на устройстве не вызывался; Kotlin lifecycle tests используют fake bridge.
SDK JDK JAVA_HOME=`E:\Programs\Android\Android Studio\jbr`; host build vcvars64+SDK CMake.
Lint/остальные tests не запускались. APK app/build/outputs/apk/debug/app-debug.apk собран,
НЕ установлен; перенос весов и device access не разрешены в этом блоке.

Engine ещё не подключён к AppContainer/UI/ModelStore; код сам модель не загружает.
Следующий блок плана — chunking DOCX/XLSX с source refs/local index и далее hybrid search;
подключение app owner и импорта весов потребуется до пользовательской индексации.
На успешной сборке пользователь просит сразу отчитываться и завершать блок.
Без нового разрешения не commit/push/install/другие tests; private examples не в Git.

## История embedding до JNI (2026-11-02)

Пользователь разрешил идти по плану с остановкой на вопросах, затем разрешил скачивание
и проверку pinned TwinSunsLLC Q8_0. Файл models/multilingual-e5-small-q8_0.gguf скачан,
hash/size132439008 совпали. См. EMBEDDING_RESEARCH.md с native smoke командами/результатами.
Без новых dependencies: предварительно текущий llama.cpp BERT/MEAN embedding API + отдельный
JNI context. Нюанс XLMRoberta tokenizer при BertModel config требует проверки артефакта.
Host CMake/MSVC probe из существующего vendor PASS: 384 finite/unit vectors, 3 synthetic
Russian retrieval top1 cases. Сначала исправлено неверное has_encoder требование harness:
BERT использует noncausal decode→encode в pinned API. App/vendor не менялись, Gradle/device не запускались.
Блокер проверки конверсии: context511 против исходных512, MASK ID→[PAD250000]. Не утверждать,
что embeddings неверны, но без reference parity к интеграции не переходить.
Пользователь разрешил host tokenizers и official tokenizer.json: tokenizers0.22.2 установлен
в models/e5-verify-env; original revision/hash и команда в EMBEDDING_RESEARCH.md.
Reference parity FAIL 16/24: 6 trailing whitespace + 2 literal special markers;
обычные RU/EN/цифры/Unicode совпадают. Native parse_special=false отличается от reference
AddedTokens; это ещё не доказательство дефекта весов. Длинные inputs проверены только как
token counts, inference boundary не выполнялся. По требованию пользователя на FAIL остановились.
Пользователь согласился на повторный разбор: normalized parity24/24 PASS (trim внешних
пробелов+разделение literal special markers), raw parity остаётся16/24.
Новый блокер: фактическая positional table GGUF384x511. Upstream conversion/bert.py
в XLMRoberta path отрезает первые1+pad rows; исходная e5 — BertModel512/pad0. Есть риск
position shift, пока без прямого сравнения не называть конверсию доказанно сломанной.
Пользователь разрешил Range comparison. `python models/verify_e5_positions.py`:
3HTTP206,808896 bytes, confirmed ALL511 GGUF rows equal official1..511; unshifted0 matches.
Позиционный сдвиг подтверждён. Results/hashes/provenance в research doc и models/*.json/bin.
Пользователь разрешил исправленную копию: выполнено `python tools/embedding/repair_e5_positions.py`.
models/multilingual-e5-small-q8_0-posfix.gguf:132440544 bytes, hash
bdb785b2ca8532ff3097f93ac3e4e1d560a122cfba377bf559895aebfd9be6d9. Оригинал сохранён,
196 non-position tensors byte-identical; official full positions512x384+context512 восстановлены.
Posfix checks PASS: normalized token parity24/24; retrieval3/3 + repeated input; actual
inference510/511/512;513 preflight rejected. Commands/results в research doc.
Следующий блок общего плана — JNI embeddings/Kotlin owner, guard512. Полной parity с
official full-precision нет; нет device RAM/latency проверки. App/vendor/device не менялись.
Зависимости/версии не добавлялись. Build/device permissions соблюдать отдельно.

## Последний блок — внешнее открытие оригиналов (2026-11-02)

Пользователь подтвердил «правки работают». Внешнее открытие принято. Попросил план и todo:
следующий предлагаемый блок — исследование multilingual-e5-small/runtime/артефакта/лицензии,
затем согласование dependencies/скачивания. Пока не начинать реализацию нового блока без согласия.
Порядок todo: embedding runtime → chunking/local index → hybrid search → grounded Qwen → XLSX analytics.

DocumentsScreen: основной клик/«Открыть оригинал» запускает ACTION_VIEW; отдельная кнопка
«Извлечённые данные» сохраняет preview. Новые OpenOriginalDocument/OriginalDocumentProvider,
xml/document_paths и provider в Manifest (без новых uses-permission). Read-only grant
конкретного оригинала, friendly error при отсутствии viewer. Импорт принят пользователем.
Разрешено «Собрать APK»: assembleDebug PASS (10 с), tests/lint не запускались, установка
этого блока затем прямо запрошена пользователем и выполнена: CPH2581,
`adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
Первая compile error nullable openFile исправлена. Ручная приёмка получена; следующий шаг — согласование embedding-исследования.
Пользователь просит сразу сообщать об успешной сборке и завершать без лишних действий.

## Текущая задача — 2026-11-02: библиотека документов

Лобби принято пользователем. По «да делай» реализованы DOCX/XLSX import/list/preview.
`documents/DocumentModels.kt`, `OfficeDocumentExtractor.kt`, `DocumentRepository.kt`,
`DocumentsViewModel.kt`, `DocumentsScreen.kt`; AssistantRoot теперь показывает библиотеку.
Без новых dependencies/Manifest/native/версий. Оригиналы и JSON только в filesDir/documents.
DOCX — основной текст и таблицы, изображения пропущены. XLSX — структуры/raw values/
формулы/cached results; без пересчёта/полной Excel-вёрстки. Shared formulas без текста
явно помечены. Сохранены координаты, система дат, пустые листы; скрытые данные включены.

SHA-256 не допускает дубля READY, ERROR можно повторно импортировать. AtomicFile для JSON,
последовательный import на IO, отмена кооперативная. Прерванный process-death import виден
как ERROR. Preview по листам и 50 блокам. Максимум файл50МБ/XML64МБ/text4млн/cells100тыс.
Реальный XLSX с 283180 XML cells потребовал исключить пустое оформление из cell limit.
Примеры приватные: /doc_examples/ в .gitignore, в Git и логи содержимое не добавлять.

Пользователь отдельно разрешил «Сборка и тест парсера». Последний запуск:
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests "com.example.localassistant.documents.OfficeDocumentExtractorTest"`
PASS, 6 tests/0 failures/0 skipped, включая 4 локальных примера. Первый прогон FAIL на
cell limit, исправлен. Скомпилированы test sources, остальные test cases не запускались.
JAVA_HOME=`E:\Programs\Android\Android Studio\jbr`; module/tasks проверены. Diff check PASS.
Lint/Android UI/SAF/persistence на телефоне не проверялись. По прямому запросу пользователя
новый APK установлен на CPH2581: `adb -s 2d46124b install -r` — Success, без очистки данных.
APK: app/build/outputs/apk/debug/app-debug.apk. Следующий шаг — ручной import/preview/restart;
новые device/install действия только по запросу. Не запускать другие tests/lint без согласия.

Далее отдельно согласовать multilingual-e5-small/runtime, гибридный индекс, RAG, вычисления.
PDF/OCR отложены. Не commit/push без нового разрешения; старый commit ниже — прошлый этап.

## Где остановились — 2026-10-28

Пользователь выбрал вариант 1: реальные Google Tasks public API, без нативного времени.
Также сообщил, что включил Tasks API и добавил tasks scope в Cloud Data Access.
Tasks lifecycle/search и последняя source-selection сборка приняты пользователем на OnePlus:
«отлично теперь все работает». APK установлен через install -r — Success, данные сохранены.
Запрошен commit текущей реализации. Сообщение: feat(calendar): unify event and task workflows.
Capture завершён: соответствующий PID уже отсутствовал. Последние HTTP-результаты не
считывались, приёмка — подтверждение пользователя, не выдуманный полный test PASS.
Parent checkpoint b1fb5e7. Hash нового checkpoint определить git log; push не запрашивался.

## Что сделано

- CalendarScopes.REQUIRED: calendar.events + tasks (полный CRUD задач).
  AuthManager connect/check/ensure/revoke согласованы; частичный reported grant отклоняется.
- GoogleApiHttp общий для двух API: safe route templates, fixed error categories,
  без body/header/token/query values. Старый transport из HttpCalendarGateway вынесен,
  Event endpoints и bounded pagination сохранены. GoogleApiAuthorization — одна retry policy.
- HttpGoogleTasksGateway + AuthorizedTasksGateway: tasklists catalog, task pages,
  GET/POST/PATCH/DELETE; Read401 replay только idempotent, writes никогда не повторяются.
- CalendarEvent — legacy name общей projection, НЕ превращение Task в Calendar event.
  kind TASK, listId/listTitle/status/parent/assignment; реальные Task refs GoogleTaskRef
  key(listId,taskId). Event refs не проходят в Task adapter и обратно.
- CalendarSession остаётся единственным owner kind/object/op/draft/targets/catalog/awaiting.
  Нет отдельного TasksViewModel или второго intent cache. Все source switches проверяются.
- CalendarTurnPolicy различает «задача»/«событие», list names, завершение/undo/filter status.
  «Список задач» = сами задачи, «списки задач» = catalog. task list in quotes не становится
  Task title. Time unsupported → сохранённый Task draft + «без времени» для продолжения.
- UI выбор списка при create (если несколько), same target choice/confirmation для Tasks.
  Task create date optional; due без timezone shift; checkbox completion, date groups,
  undated section, time unavailable footer. Не фальсифицировать Task как all-day Event.
- GoogleTaskUpdate: partial title/notes/due/status, clear due, no-op. При UPDATE date-slot
  reply идёт в destination, а не в source date, если это ожидаемый новый параметр.
- Общий executor resolve/confirm/preflight/apply используется обоими источниками.
  Никаких Calendar Event companions, локальных reminders, недокументированных API.
- Source inheritance теперь только refs/slots/field edits/temporal reply, не новый subject.
  Event request очищает Task filters. Reference проверяется у command argument, не по
  случайному «его» в имени. TITLE reply со словом Event сохраняет текущий Task kind.
  Empty read может продолжить query без fake targets, write всё ещё требует реальные refs.
- Родитель с subtasks блокируется от скрытого cascade; assigned delete показывает эффект
  на исходную задачу Docs/Chat. Subtask CRUD/creation не добавляли, parent guard best effort.

## Проверки

`.\gradlew.bat :app:assembleDebug` — SUCCESS (финальный прогон 1 с).
`git diff --check` — успешно. Unit/UI tests/test compilation НЕ запускались по запрету;
lint не запускался. GoogleTasksWorkflowTest написан (source routing/date-only/complete/undo/
cancel/key identity), CalendarSelectionRegressionTest актуализирован, это НЕ runtime PASS.
CalendarSourceSelectionTest добавлен для последней правки; не запускался/не компилировался отдельно.
Имеющиеся deprecated TRIM_MEMORY_RUNNING_LOW/LocalLifecycleOwner — прежние warnings.
APK: `E:\Programs\Android\AndroidStudioProjects\LocalAssistant\app\build\outputs\apk\debug\app-debug.apk`.
Схема/руководство: CALENDAR_FLOW.md, GOOGLE_TASKS.md, DECISIONS.md/D022.

## Следующий шаг

Завершить запрошенный commit и сообщить hash/состав. Исключить AGENTS.md,
CALENDAR_IMPLEMENT_PROMPT.md, docs/CALENDAR_CHAIN_REVIEW.md и calendar-auth.log.
После commit выбирать следующую задачу с пользователем, без автоматического push.
При будущем разрешённом update/capture: SDK adb
`%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`, только CalendarAssistantDebug:I *:S.
Как только capture запущен, СРАЗУ остановиться и сказать «можно проверять», ждать «готово».
Не чистить storage/чат/модели/logcat, не выполнять Google-мутации вместо пользователя.

Оба source работали по подтверждению пользователя. Source fix не добавлял scopes;
reauth только если приложение запросит. Console доступа у агента нет.

Ручные сценарии последней приёмки (пользователь подтвердил работоспособность):
0. После Task lifecycle standalone вопрос без «задача» → EVENT/Calendar endpoint/header;
   явная «задача» → TASK; «Отметь её выполненной»/«А завтра?» сохраняют связанный source.
1. «Покажи списки задач», «Мои задачи», «Найди задачи на этой неделе»: TASK endpoint/labels,
   не Event mixed results; включены completed/hidden/assigned, period по due, не completed time.
2. Create Task name+date без часов → list choice → actual confirmation → POST tasks;
   потом title/notes/due update, complete/undo, delete после confirm.
3. Task без date создаётся/читается в all-dates; explicit date range исключает undated.
4. Clock/duration в Task не записываются; после «без времени» сохраняются остальные поля.
5. Event READ/SEARCH/rename/weekdays/time-only UPDATE regression, type switching/Cancel/new-chat.
6. Google UI сверить именно native Tasks (checkbox), а не ранее созданные pseudo-task Events.

## Ограничения/данные

Catalog100/result500/batch100/range90, без silent truncation; batch не atomic; process-memory.
List CRUD/move между списками/recurrence/native Task time не реализованы. Нужны для времени
другие варианты, пользователь выбрал date-only public API. Никаких fake 00:00/all-day claims.
Ранее «задачи», созданные Events adapter, не мигрировались/не удалялись. Нужны отдельные
согласованные действия, если пользователь захочет миграцию. Notes/events/audio/secrets в docs
и logs не писать; AGENTS.md/prompt/review/calendar-auth.log не изменять.
Native/Gradle/dependencies/Manifest не менялись. Без разрешения не commit/push/install/тесты.
