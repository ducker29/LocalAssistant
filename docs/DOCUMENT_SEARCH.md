# Документный поиск, ответы и XLSX — состояние реализации

Дата: 2026-11-02. Код добавлен; assembleDebug/16 targeted tests/lintDebug PASS, device-приёмка впереди.
Обновление: автоматический поиск в основном чате и подробные ответы проверены отдельным
прогоном assembleDebug/54 targeted tests/lintDebug PASS44с; этот новый APK ещё не установлен.

## Автоматический основной чат и полные найденные фрагменты

- Переключатель не добавлять: пользователь явно отклонил такую схему. IndexedDocumentChat
  валидирует JSON route с question/recent6 turns/doc flags, reformulates standalone query
  для followup; normal/calendar сохраняют прежнюю маршрутизацию. Semantic routing качество
  на настоящем Qwen требует ручной приёмки, fake tests не доказывают accuracy.
- Документная ветка не трогает CalendarSession/prepareTurn и tools не получает.
- Ответ больше не ограничен коротким JSON384: каждый возвращённый search fragment
  попадает в подходящую по exact prompt count порцию, output до1024. Sources не отбрасываются
  ради одного контекста. Верхняя защита64 source/pieces, при превышении явное уточнение.
- Итог: подробные разборы порций + полный исходный текст ВСЕХ возвращённых поиском chunks
  (обычно top8) и app-generated locations в одном сообщении. Это не exhaustive scan архива.
- JSON cite validation сохраняется. Неудавшаяся порция явно отмечается, raw text остаётся;
  выдавать app quotes за текст, сформированный Qwen, нельзя.
- Source buttons в main chat открывают оригиналы; documentRelated/source refs persist v2.
  Raw source markers сохраняются при restart. Старые messages без полей читаются нормально.
- Cancel останавливает lookup/Qwen; requestId защищает поздние updates после newChat.
  Raw document messages исключены из ordinary chat prompt, контекст followup берёт router.
- Проверены5 классов: DetailedDocumentAnswerTest4,AutomaticDocumentChatTest5,
  ChatViewModelTest31,ConversationStoreTest4,DocumentPipelineTest10:54/54 PASS; lintDebug PASS.
  Device/real router/calendar behaviour не запускались. APK собран, новый install не выполнялся.
Пользователь разрешил все оставшиеся пункты по порядку и просил останавливаться при проблемах.

## Реализовано

- DocumentChunks: source refs(documentId/block/part/sheet), DOCX heading context,
  XLSX предполагаемые headers, точный passage token count<=512, Unicode-safe split/overlap.
- DocumentIndexStore: version/model hash/doc hash, AtomicFile binary snapshot по документу,
 384 float values; отмена/ошибка сохраняют предыдущий snapshot. Max20k/doc,25k/query archive.
- HybridDocumentSearch: BM25 Unicode words/артикулы+cosine, reciprocal rank fusion;
  scores не выдаются за confidence, нет недоказанного cosine cutoff.
- DocumentSearchService app-scoped: import pinned posfix через SAF, size/SHA validation,
  atomic private copy, serial mutex, index progress/cancel, skip valid old indices;
  load embedding lazily, unload after index/search. Memory pressure signals cancellation.
- DocumentLlm: отдельные Qwen messages на существующем engine, без Calendar tools/истории;
  exact prompt count/reserved output budget, bounded sources, JSON answer/source-ID validation,
  fixed not-found response. Source IDs проверяются приложением; entailment не доказано.
- SpreadsheetAssistant: catalog выбранных1–2 XLSX → untrusted bounded JSON plan от Qwen;
  clarification вместо выдуманных table/columns. Заголовки определяются эвристически.
- SpreadsheetCalculator: allowlist sum/average/min/max/count/lookup/compare, typed filters,
  BigDecimal arithmetic, groupBy, inclusive row range, unique-key compare/diff/percent,
  missing matches/zero base явные. Смешанные units/currency/дубликаты ключей отклоняются.
  Cached formula values — отдельный UI checkbox, по умолчанию false; Excel не пересчитывается.
- date1904 хранится структурированно, старые content JSON читаются с восстановлением
  известного extractor warning. Содержимое документов и оригиналы не изменяются.
- DocumentsViewModel/UI: импорт embedding, индексация/статусы/отмена, query/Найти/Ответ ИИ,
  реальные source links, выбор XLSX/расчёт/result. Back отменяет работу, rotation не отменяет.
- AppContainer создаёт сервис и DocumentLlm; Qwen загружается через существующий Чат.
  Нет новых dependencies/permissions/версий. Embedding artifact не в assets/APK.

## Запущенные проверки и блокер

Пользователь отдельно выбрал «Сборка, тесты и lint»:
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests "com.example.localassistant.documents.DocumentPipelineTest" --tests "com.example.localassistant.documents.OfficeDocumentExtractorTest" :app:lintDebug`
(JAVA_HOME=JDK Android Studio).

Результат: BUILD FAILED (50с), компиляция Kotlin/native прошла;16 tests,1 failure.
OfficeDocumentExtractorTest6/6 PASS включая4 локальных примера. DocumentPipelineTest9/10:
chunk limits/Unicode/header/source/lexical article/RAG refs/BigDecimal/group/filter/formulas/
currency/keys/cross-book/date1904/unknown plan проверки прошли.

FAIL `subtotal rows are not silently counted twice`: `Regex("(?i)^(итого|...)...")`
не распознаёт uppercase кириллицу «Итого» (Java inline CASE_INSENSITIVE без Unicode case).
В результате TOTAL строка могла попасть в sum и удвоить итог. После остановки пользователь
отдельно разрешил fix/повтор: только RegexOption.IGNORE_CASE вместо inline (?i); тест не менялся.

Повтор той же команды — BUILD SUCCESSFUL19с: assembleDebug/packageDebug PASS,16 tests PASS,
lintDebug PASS. APK app/build/outputs/apk/debug/app-debug.apk содержит весь новый код.
Установка и перенос модели не выполнялись; требуют отдельного запроса. Следующий этап —
ручная приёмка на телефоне: импорт posfix GGUF → индекс → поиск → Qwen → XLSX calculations.

## Ещё не проверено

- Реальный Android SAF/AtomicFile индексов, JNI runtime на телефоне, memory/latency/нагрев.
- Качество реального русского поиска/Qwen structured plans; synthetic tests не заменяют это.
- Автоопределение сложных заголовков/нескольких таблиц на листе; результат явно показывает
  заголовок/range/filter, при неоднозначности нужно уточнение, не обещать произвольный Excel.
- Все прочие unit/UI tests не запускались. lintDebug завершился успешно; это не device-проверка.
- Модель не переносилась на телефон и APK не установлен. Device/install только по запросу.
