# Текущее состояние проекта

## Запрос на общий локальный коммит (2026-11-03)

- Пользователь явно разрешил коммит всех накопленных изменений. Включить source/tests,
  build configuration, документацию, tools и пользовательские AGENTS/prompt/review.
- calendar-auth.log оставить локально как authorization diagnostic; ignored модели/примеры/
  screenshots/build outputs/local.properties/secrets не включать. Ничего не удалять и не push.
- Последние проверки production-кода: assembleDebug/460 targeted tests/lintDebug PASS28с;
  APK затем установлен на CPH2581 с сохранением данных. Нового device access не запрашивали.
- Ручная приёмка последнего time/date/pending-action fix ещё впереди; сведения ниже актуальны.

## АКТУАЛЬНО: pending move/clock и междневные переносы (2026-11-03)

- «на N часов» ошибочно вызывало ранний CLARIFY до draft. Теперь0..23 без относительных
  markers =clock; relative/invalid требует уточнения, но цель/operation/slots уже staged.
- app-owned clarificationFields DATE/TIME → executor await после target resolution;
  bare «12» продолжает тот же task, повторный invalid сохраняет точный awaited field.
- GENERAL remark не сбрасывает action; новый Calendar action/отмена/newChat/account reset
  перекрывают его. Runtime-session memory, не process-death persistence.
- Мoved known IDs не переискаются в старом source-day: cached API match +fresh GET/validation,
  отдельное confirmation и pre-write stale check. Date-only сохраняет original time/duration.
- По отдельному разрешению assembleDebug +13 suites +lintDebug PASS28с,
  460tests/0failed/errors/skips. Подробности CALENDAR_SEQUENTIAL_EDITS.md.
- Новый APK установлен по прямому запросу на CPH2581:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
  Live Calendar не читался; следующий шаг — manual time/date/clarification QA.

## АКТУАЛЬНО: последовательные Calendar edits/запрет имитации (2026-11-03)

- Скриншоты показали subject-first time command. Распознавание расширено ограниченно;
  ambiguous Calendar writes → app clarification, не normal Qwen. Отчёты Calendar исключены
  из ordinary prompts (metadata v4 +legacy format); отдельный backstop при отсутствии executor.
- CalendarSession original resultSet/day отделены от active narrowed targets; known named
  UPDATE ко второй записи использует original scope. Только actual API success обновляет набор.
- Confirm перед каждым новым changed write сохранён. No-op/cancel/error не маскируются
  под successful update. Подробнее CALENDAR_SEQUENTIAL_EDITS.md; private screenshots ignored.
- Разрешённые assembleDebug/12targeted suites/lintDebug PASS7с,453 tests. До PASS исправлены
  две ошибки synthetic fixtures (RFC3339 seconds/UTC vs local hour), не safety assertions.
- Новый APK установлен по прямому запросу на CPH2581:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
  Google Calendar не читался; следующий шаг — manual repeat: read2→confirm first→confirm second.

## АКТУАЛЬНО: Calendar tense-only остаток «были» (2026-11-03)

- Предыдущий fix «у меня сегодня» пользователь принял. Новый reported past Saturday
  запрос оставлял «были» как QUERY при верной дате. Scope только Calendar false-empty.
- Whole-service-only READ cleanup дополнен были/было/был/будут/будет/есть; реальные query
  words/quoted names не очищаются. Добавлены exact/returned-event/quoted-title regressions.
- RelativeDateQueriesTest теперь350 checks (70×5, включая tense+possessive framing).
- Разрешённый assembleDebug/6calendar suites/lintDebug — BUILD SUCCESSFUL19с,
  399tests/0failures/errors/skips. Exact past Saturday range проверен synthetic clock/gateway.
- Свежий APK установлен по прямому запросу на CPH2581:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
  Следующий шаг — exact manual query сSwitchOFF. Google/live events не читались.

## АКТУАЛЬНО: Calendar «у меня сегодня» — воспроизведено и исправлено (2026-11-03)

- Пользователь попросил решать только Calendar false-empty. Сначала добавлены exact/variant
  tests; по отдельному согласию ожидаемый red test подтвердил request.query="у меня".
- Минимальный fix в CalendarTurnPolicy: при READ остаток, целиком состоящий из service
  phrases «у меня»/«в календаре», очищается; quoted names и реальные query words остаются.
- Добавлены executor regression с synthetic returned event и280 relative-date checks
  (70 scenarios×4, включая possessive full question); quote «У меня» остаётся SEARCH.
- Разрешённые assembleDebug/6calendar suites/lintDebug — BUILD SUCCESSFUL42с,
  326tests/0failures/errors/skips. Предшествующий1red test был намеренным воспроизведением.
- APK app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу на CPH2581:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, данные сохранены.
  Live Calendar не читался; следующий шаг — точная ручная проверка сSwitchOFF.
- Остальные проблемы отложены по просьбе пользователя; Calendar API/account/permissions не менялись.

## АКТУАЛЬНО: explicit doc mode/short/full и Calendar corrections — PASS (2026-11-02)

- По «доделывай и запускай тесты» исправлены Unicode boundaries (CalendarRegex) в3 calendar
  parser files, qualifiers current/previous/next для всех7 weekdays и exact RAG test exception.
- Java25 probe подтвердил default boundary mismatch; JDK/версии/флаги не менялись.
- Финал: assembleDebug +11 targeted suites +lintDebug — BUILD SUCCESSFUL34с,
  308 tests/0 failures/errors/skips, включая210 relative date checks. Прежние39failures устранены.
- APK app/build/outputs/apk/debug/app-debug.apk со встроенной моделью готов к ручной приёмке,
  По прямому запросу установлен на CPH2581: `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success,
  без очистки данных. Следующий шаг — device check OFF/Calendar/ON/short→full.
  Реальные Calendar/Qwen пока не проверены; актуальные детали в CHAT_DOCUMENT_MODE.md.

- Пользователь дополнительно запросил relative days/weeks/qualified weekdays tests.
  Добавлен CalendarRelativeDateQueriesTest:70 scenarios/210 checks, НЕ запускались.
  Fixed clock/zone, all5 relative days,3 week directions,all7 weekdays/3 qualifiers/cases,
  quote/query stripping, exact bounds/year/week/DST. Details: CHAT_DOCUMENT_MODE.md.
  Weekday adjective parsing по исходникам тоже требует исправления; runtime пока не менялся.

- Пользователь сменил требование: Switch «Поиск по документам» вместо auto-route. OFF по
  умолчанию жёстко исключает doc service из send; ON только документы. UI Switch добавлен.
- Короткий processed answer384; «Полный ответ» рядом сCopy только у primary short приON.
  Full iterative по immutable question/chunks snapshot, отдельным сообщением, без raw dump.
  Snapshot/source refs persistv3; ошибка не заменяется technical evidence. См. CHAT_DOCUMENT_MODE.md.
- Calendar punctuation trim до service cleanup внесён: вопрос «...в календаре завтра?»
  мог оставлять QUERY «в календаре». Проверка выявила дополнительную проблему границ слов.
- Разрешённый run: assembleDebug+9targeted classes+lintDebug — FAIL38с, APK собран,
  95 tests/39 failures:38 calendar (вероятно Unicode `\b` на JDK25),1 wrong expected exception
  в новом invalid-citation test (production validator корректно отклоняет unknown source).
- ОСТАНОВЛЕНО: Unicode boundary diagnosis/fix и test expectation ещё НЕ внесены.
  Mode/history/ordinary chat и остальные pipeline/RAG tests PASS. Final lint PASS не получен.
- Предыдущая остановка затем разрешена и закрыта308 PASS. APK НЕ установлен;
  пользовательский Calendar/Google/device не читались, версии/permissions не менялись.

## Предыдущий UX (заменён explicit mode) — автоматический документный чат (2026-11-02)

- Пользователь отверг переключатель: обращения к документам должны работать в любой
  момент обычного диалога. Переключатель НЕ добавлен.
- AppContainer.documentChat/IndexedDocumentChat: context-aware checked JSON route по
  последним6 complete сообщениям; ordinary/calendar → прежний путь, документы → индекс.
  prepareTurn/calendar state для документного запроса не вызываются/не изменяются.
- DocumentLlm.answer обрабатывает ВСЕ возвращённые hits (сейчас search top8), делит по
  реальному Qwen budget, output до1024/порцию вместо384. Подробный разбор + полные исходные
  тексты всех найденных фрагментов в одном сообщении. Это НЕ обещание полноты всего архива.
  Invalid/budget JSON сообщает о неудаче порции и сохраняет raw sources, не выдумывает ответ.
- ChatMessage/ConversationStore version2 сохраняют documentRelated/source refs; raw source
  spellings не strip после restart. Original buttons доступны прямо в chat bubbles.
  Raw retrieved turns исключены из обычного chat context; маршрутизатор видит краткий
  recent dialogue с флагами для «а подробнее?». Cancel/newChat guard от late updates добавлен.
- По отдельному согласию assembleDebug + DetailedDocumentAnswerTest/AutomaticDocumentChatTest/
  ChatViewModelTest/ConversationStoreTest/DocumentPipelineTest +lintDebug: PASS44с,
  54tests,0 failures/errors/skips. Реальные Qwen routing/relevance/device ещё не проверены.
- Свежий bundled APK app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, CPH2581,
  без очистки данных. Следующий шаг — ручная проверка неявных вопросов/«подробнее»/обычного
  чата/Calendar regression. Код календаря/версии/dependencies/permissions не менялись.

## Предыдущий блок — bundled E5, сборка и проверки PASS (2026-11-02)

- Пользователь явно разрешил встроить posfix GGUF в APK, автоматическую фоновую подготовку
  и reuse после проверки hash. Код Gradle task/helper/service/UI/4tests добавлен.
- Отдельно разрешены assembleDebug+BundledE5ModelTest+lintDebug и ZIP hash/size verification.
- BUILD FAILED7с при configure: AGP не допускает Provider в SourceSet assets.srcDir.
  Task/compile/tests/lint не выполнены; свежего bundled APK нет, устройство не трогали.
- После остановки пользователь разрешил fix/повтор. Регистрация через androidComponents /
  variant.sources.assets.addGeneratedSourceDirectory, без upgrades/flags/manual preBuild dependency.
- Повтор команды: BUILD SUCCESSFUL1m5s; assembleDebug,4 BundledE5ModelTest,lintDebug PASS.
- APK ZIP entry size132440544/SHA256 posfix и NOTICE проверены — PASS.
  app/build/outputs/apk/debug/app-debug.apk:172267453 bytes (164.29MiB).
- По прямому запросу bundled APK установлен на CPH2581:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success,
  без очистки данных. Следующий шаг — пользователь проверяет первую подготовку/reuse и индекс.
  Android extraction/reuse ещё не приняты. Подробнее BUNDLED_EMBEDDING.md.

## Предыдущий блок — поиск/RAG/расчёты XLSX: сборка и целевые проверки PASS (2026-11-02)

- Пользователь разрешил все оставшиеся пункты. Добавлены chunking/binary atomic index,
  app-scoped embedding import/owner, hybrid BM25+vectors, Qwen grounded JSON answers,
  validated XLSX plans/BigDecimal/group/filter/compare и UI. См. DOCUMENT_SEARCH.md.
- Отдельно разрешены assembleDebug + DocumentPipelineTest + OfficeDocumentExtractorTest + lintDebug.
- Запуск BUILD FAILED50с: Kotlin/native compilation PASS;15/16 tests PASS (parser6/6 с4 примерами).
  FAIL: строка «Итого» не распознаётся subtotal guard из-за inline (?i) без Unicode case.
- По отдельному разрешению исправлен только SpreadsheetCalculator.totalLabel:
  RegexOption.IGNORE_CASE вместо inline (?i), с Unicode-case для кириллицы. Тест не менялся.
- Повтор той же команды: BUILD SUCCESSFUL19с; assembleDebug PASS,16 целевых tests PASS,
  lintDebug PASS. APK: app/build/outputs/apk/debug/app-debug.apk. APK не установлен, device не трогали.
- После успешных проверок пользователь прямо запросил обновление на подключённом устройстве:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success, CPH2581.
  Данные не очищались; embedding-веса не переносились. Ручная приёмка ещё не получена.
- Новых dependencies/permissions/версий нет; private examples/weights не в Git.
- Следующий шаг: ручная device-проверка индекса/поиска/Qwen/расчётов. Установка APK и перенос
  models/multilingual-e5-small-q8_0-posfix.gguf — только по отдельному запросу.
  Реальная точность Qwen/скорость/RAM и Android SAF/индекс ещё не подтверждены.

## Предыдущий завершённый блок — embedding JNI/Kotlin runtime (2026-11-02)

- Пользователь одобрил отдельный runtime. Реализованы embedding_runtime.{h,cpp},
  embedding_jni.cpp и embedding/ Kotlin Engine/Native/Text/VerifiedE5Model.
- Независимый от Qwen model/context; общий backend init once, без backend_free при unload.
  MEAN/noncausal,384 finite/L2,512 total tokens preflight на Kotlin и C++, UTF8 ByteArray JNI.
- Kotlin: один worker, Mutex, StateFlow unloaded/loading/ready/running/failed/closed;
  pinned posfix hash+size перед load, query/passage prefix и search-only input policy.
  Coroutine/manual cancel сигналит atomic native abort; освобождение ждёт завершения JNI;
  отменённый контекст выгружается перед повторным использованием. Close идемпотентный.
- CMake включает новые cpp, llama_jni.cpp только предоставляет общий backend-init wrapper.
  third_party/версии/зависимости/Manifest этого блока не менялись.
- По отдельному разрешению:
  `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests "com.example.localassistant.embedding.E5EmbeddingEngineTest"`
  — BUILD SUCCESSFUL (1m1s);6 tests/0 failures/errors/skips, обе ABI arm64-v8a/x86_64 собраны.
- Host `embedding_runtime_probe` с production embedding_runtime.cpp — PASS: ranking/finite384/
  unit/repeat,510/511/512 inference,513 rejection,cancel flag/unload/reload/unavailable guard.
  Kotlin fake проверяет ожидание завершения blocked native перед unload/destroy и cancel.
- На телефоне runtime НЕ проверялся; APK НЕ установлен. UI/ModelStore/AppContainer ещё
  не подключают engine — модель не загружается автоматически. Device RAM/latency неизвестны.
- Следующий блок: разбиение DOCX/XLSX на фрагменты с источниками и локальный индекс;
  также понадобится связать импорт/загрузку embedding-весов и lifecycle owner с приложением.
- Lint/остальные tests не запускались. После успеха проверки не расширять этот блок.

## История embedding-исследования до JNI (2026-11-02)

- Пользователь разрешил последовательное выполнение плана с остановкой при вопросах.
- Исследована multilingual-e5-small: MIT, 384 dims, 512 tokens, mean pooling, query/passage,
  L2. В текущем llama.cpp есть BERT и embedding API; JNI приложения их ещё не предоставляет.
- Найден pinned кандидат TwinSunsLLC Q8_0 (132439008 bytes); детали и SHA-256:
  docs/EMBEDDING_RESEARCH.md. Это сторонняя конверсия; совместимость и качество не проверены.
- Нюанс: BertModel config + XLMRoberta tokenizer, GGUF требует проверки токенизации.
- Пользователь разрешил скачивание и проверку: models/multilingual-e5-small-q8_0.gguf,
  размер/SHA256 совпали. Native host probe из текущего vendor собран и выполнен: PASS,
  384 finite values/unit norm, 3/3 synthetic Russian top1. App/vendor не менялись.
- Вопрос конверсии: GGUF context511 вместо upstream512, mask ID соответствует [PAD250000].
  Это не доказанный провал retrieval, но reference parity и границы ещё не проверены.
- По отдельному согласию созданы models/e5-verify-env, установлен tokenizers0.22.2,
  скачан pinned официальный tokenizer.json. Reference parity FAIL: совпали16/24.
  6 различий trailing whitespace, 2 literal special markers (native parse_special=false).
  Обычные проверенные RU/EN/цифры/Unicode совпали; это не доказательство плохих весов.
- По согласию пользователя после общей подготовки search input (trim+разделение literal
  special markers) parity24/24 PASS. Raw parity16/24 не изменилась. Исходные документы не меняются.
- Новый positional риск: GGUF position_embd.weight shape384x511; upstream XLMRoberta converter
  обрезает первые1+pad строк, а исходная модель BertModel512/pad0. Возможно смещение строк.
- По отдельному согласию `python models/verify_e5_positions.py` подтвердил positional shift:
  3 HTTP206,808896 bytes; ВСЕ511 строк GGUF == official rows1..511, не0..510.
- По согласию выполнено исправление отдельной копии: models/multilingual-e5-small-q8_0-posfix.gguf,
  132440544 bytes, SHA256 bdb785b2ca8532ff3097f93ac3e4e1d560a122cfba377bf559895aebfd9be6d9.
  Official positional table512x384/context512, 196 остальных tensors byte-identical;
  исходный artifact сохранён. Скрипт: tools/embedding/repair_e5_positions.py.
- Проверки posfix PASS: normalized tokenizer24/24, Russian retrieval3/3, finite384/unit norm,
  repeated input stable, реальный inference510/511/512 total tokens;513 rejected preflight.
  Это host checks, не device и не full numerical parity. Новых downloads/deps нет.
- Следующий блок плана: Android JNI embedding context и main-safe Kotlin owner с512-token
  guard. App/vendor/device пока не менялись. Research doc содержит команды/provenance/hashes.
  Gradle/device этого блока не запускались. Подробности: EMBEDDING_RESEARCH.md.

## Последнее изменение — открытие оригиналов, 2026-11-02

- Пользователь подтвердил «правки работают»: внешнее открытие оригиналов принято.
  Todo обновлён: следующий предлагаемый блок — исследование multilingual-e5-small,
  runtime/артефакта/лицензии/ресурсов; затем согласование зависимостей и загрузки весов.
  Этот следующий блок ещё не начат; ожидает согласия. Далее chunking/index/hybrid search,
  ответы Qwen с источниками, отдельно XLSX analytics.

- Пользователь подтвердил импорт и готовность документов к просмотру; запросил нативный
  вид через установленные программы. Добавлены ACTION_VIEW и read-only FileProvider
  только для original.docx/xlsx, кнопки «Открыть оригинал»/«Извлечённые данные».
- Если совместимого приложения нет, показывается сообщение; повторный импорт не нужен.
- Разрешена только сборка: assembleDebug PASS (10 с). Первая попытка выявила nullable
  return FileProvider.openFile; исправлено явным FileNotFoundException для null.
- По прямому запросу сборка внешнего открытия установлена на CPH2581:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success.
  Данные сохранены; runtime-проверка/tests/lint этого блока не выполнялись.
- Следующий шаг: согласовать исследование embedding runtime; device-приёмка внешнего открытия получена.
- После успешной сборки оперативно сообщать результат и останавливаться без расширения задачи.

## Активная задача — 2026-11-02: импорт DOCX/XLSX

- Лобби/навигация приняты пользователем после пересборки и установки: «теперь правки
  работают». Первый install использовал старый APK; всегда собирать текущий код перед install.
- Реализованы импорт нескольких DOCX/XLSX через SAF, приватная копия оригинала,
  SHA-256 для дублей, атомарные metadata/content JSON, статусы и просмотр содержимого.
- `documents/`: модели, Repository, ViewModel, DocumentsScreen, OfficeDocumentExtractor.
  AssistantRoot подключает настоящий экран библиотеки; существующие чат/календарь сохранены.
- ZIP/SAX streaming без новых зависимостей: DOCX основной текст/таблицы, картинки игнорируются;
  XLSX листы/строки/координаты/типы/raw values/формулы/сохранённые результаты/система дат.
  Формулы не пересчитываются; общие формулы без собственного текста помечаются явно.
- Preview: листы и страницы по 50 фрагментов; ошибки одного импорта не прерывают остальные.
  Process-death прерывание отмечается ошибкой; повторный импорт повторяет извлечение.
- Лимиты: файл 50 МБ, XML 64 МБ, 4 млн XML text chars, 100 тыс. непустых/формульных ячеек.
  Пустое оформление не считается: реальный пример имеет 283180 XML cell records.
- `doc_examples/` добавлен в .gitignore. Содержимое документов не логируется/не копируется в docs.
- Пользователь явно разрешил assembleDebug и только OfficeDocumentExtractorTest.
  `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests
  "com.example.localassistant.documents.OfficeDocumentExtractorTest"` — SUCCESS (финал 2 с).
  6 tests, 0 failed/skipped; включены все 4 локальных примера. Первый прогон выявил подсчёт
  пустых оформленных ячеек; исправлено. `git diff --check` PASS.
- JAVA_HOME для команд: `E:\Programs\Android\Android Studio\jbr`. Module/tasks проверены ранее.
- Lint/остальные unit/UI tests не запускались; Android SAF/persistence/lifecycle ещё не проверены.
  По прямому запросу пользователя новая сборка библиотеки установлена на CPH2581:
  `adb -s 2d46124b install -r app/build/outputs/apk/debug/app-debug.apk` — Success.
  Данные приложения не очищались. APK: app/build/outputs/apk/debug/app-debug.apk.
- Следующий шаг: ручная проверка импорта/просмотра/перезапуска на CPH2581; install/device access
  только по новому запросу. Embeddings/RAG/расчёты ещё не реализованы; PDF/OCR отложены.
- Начальный embedding-кандидат: multilingual-e5-small, runtime/артефакт ещё не выбраны.
- AGENTS.md и untracked prompt/review/auth log — пользовательские, не изменялись.

## Контрольная точка — 2026-10-28

Пользователь принял последнюю сборку после ручной проверки на OnePlus CPH2581:
«отлично теперь все работает» и явно попросил commit. Ранее отдельно приняты поиск
и жизненный цикл Google Tasks. Source-selection fix установлен через install -r — Success.
Default нового запроса без «задача» = EVENT; refs/slots сохраняют реальный kind.
Захват логов завершён; при проверке PID процесс уже отсутствовал. Отдельные HTTP-результаты
последнего прогона не собирались: приёмка зафиксирована по сообщению пользователя.

## Единая Event/Task-система

- Один CalendarSession: taskId/version/phase/draft/targets/awaiting/selector/pendingSubject.
  kind EVENT/TASK принадлежит приложению; нельзя применить Task op к Event refs и наоборот.
- Task ID квалифицирован listId+taskId через GoogleTaskRef, а не произвольная model string.
- CalendarEvent остаётся именем общей display projection, но kind/Task metadata сохраняют
  настоящий источник. TASK создаётся только Tasks API, без companion Event/local reminder.
- Модель получает snapshot + app operation/kind/transition и предлагает лишь field patches.
  Старый unsupported-Tasks guard удалён, поля list/status добавлены без новых CalendarOp.
- Общие resolve/choice/prepare/confirm/refetch/apply, no-op/partial outcomes и format-by-date.
  GoogleApiHttp и GoogleApiAuthorization общие; 401 retry только чтений, никогда записей.
- Google Tasks: каталог списков/выбор, чтение/поиск по спискам и due, создание, title/notes/
  due update/clear, completed/needsAction, delete. Без периода — также undated/completed/
  hidden/assigned задачи, с периодом фильтрация по due date, не времени выполнения.
- due трактуется как дата, не Instant; UI Task не выдаёт неизвестное время за «весь день».
  Запрос clock/duration сохраняет draft и просит убрать время, не создавая Event вместо Task.
- Родитель с подзадачами не удаляется скрытым cascade; assigned delete отмечен в confirmation.
- Task list catalog находится в Session, выбор списка — отдельная UI phase той же задачи.
- Новый Event request не наследует task list/status filters. Possessive pronoun внутри
  нового имени не считается ссылкой на старую задачу. Пустой read может продолжить query,
  но write по-прежнему требует настоящих targets. TITLE slot не меняет kind на слово Event.

## OAuth и ручные шаги

- REQUIRED = calendar.events + https://www.googleapis.com/auth/tasks.
- Connect/check/silent authorization/revoke используют один согласованный набор scopes;
  явно неполный grant отклоняется. Tasks-функции приняты пользователем на устройстве.
- Google Cloud Library: Tasks API, Data Access: tasks. Пользователь сообщил, что выполнил.
- Не удалять данные/историю/модели; reauthorization не требует очистки storage.
- Подробная инструкция и ограничения: GOOGLE_TASKS.md, схема: CALENDAR_FLOW.md, D022.

## Проверки и следующий шаг

- `.\gradlew.bat :app:assembleDebug` — SUCCESS (финальный прогон 1 с).
- `git diff --check` — успешно.
- Unit/UI tests и отдельная test compilation НЕ запускались по текущему запрету пользователя;
  lint не запускался (согласована сборка). Добавлен GoogleTasksWorkflowTest, старые source
  regression tests актуализированы; наличие тестов не является PASS.
- Source-selection APK установлен, ручная приёмка принята пользователем.
  Текущий checkpoint подготовлен к commit по прямой команде; push не запрашивался.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Следующий шаг: завершить запрошенный commit, затем выбирать новую задачу с пользователем.
  Новые install/device access/проверки требуют разрешения в соответствующей задаче.

## Фон и границы

- Приняты ранее: Event READ/SEARCH/CREATE, single PATCH200/DELETE204, date-group headings,
  multi-turn создание, перенос с сохранением длительности. Последний набор ручных сценариев
  принят; полный автоматический regression matrix (DST/лимиты/ошибки) этим не доказан.
  Логи не содержат private titles/query/token/body; capture завершён.
- Lists: просмотр/выбор, не CRUD самих списков; move между списками не реализован.
  Scheduled time/duration/recurrence не предоставляет текущий public Tasks adapter.
- Limits: catalog100, result500, batch100, date range90. Batch не транзакция; in-memory state.
- M1 завершена; M2 локальный Qwen/история/продолжение и M3 STT реализованы, прошлые
  проверки — IMPLEMENTATION_REPORT.md. Вне scope RAG/заметки, physical STT, release/нагрев.
- Версии/зависимости/Manifest/native не менялись. Parent checkpoint b1fb5e7 сохранён.
  Пользовательские AGENTS.md/prompt/review/auth log не включать в feature commit.
