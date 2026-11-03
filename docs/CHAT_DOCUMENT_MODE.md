# Документный режим, короткий/полный ответ и календарь

## Calendar tense-only followup — 2026-11-03

Пользователь принял fix «у меня», сообщил false-empty для «какие события были в прошлую
субботу?» с верной датой. READ service-only остаток теперь включает copula forms
были/было/был/будут/будет/есть. Whole-only, quoted literal names сохраняют поиск.
Добавлены3 regressions (exact+варианты, fake returned-event не отбрасывается, quoted guard),
relative-date matrix70×5=350 сtense+possessive phrasing. Общий targeted399/399 PASS,
assembleDebug/lintDebug PASS19с. Затем APK установлен по прямому запросу на CPH2581:
adb install -r — Success, данные сохранены. Live Google не читался.
Следующий шаг — ручная проверка exact past query сSwitchOFF.

## Дополнительный Calendar fix — 2026-11-03

Live пользователь сообщил false-empty у «какие события у меня сегодня?». Новые тесты
с fixed clock воспроизвели request.query="у меня" до production change (1test expected FAIL).
Fix: только READ и только весь остаток из service phrases «у меня»/«в календаре» очищается.
Реальные слова фильтра и quoted literal «У меня» не стираются. API/диапазоны не менялись.
DateRead7/7 и Relative280/280 плюс Selection12/Source6/Temporal18/Regex3:326tests PASS;
assembleDebug/lintDebug PASS42с. APK затем установлен по прямому запросу на CPH2581 через
adb install -r — Success, без очистки данных. Live Calendar не проверялся агентом.
Ручная приёмка exact request сOFF впереди. Другие жалобы пока не изменять.

Дата: 2026-11-02. Scope явно подтверждён пользователем «делай».
Статус: corrections завершены; assembleDebug/308 targeted tests/lintDebug PASS34с.

## Завершение календарных исправлений

Пользователь разрешил доделать и запустить расширенный набор. Отдельный stdlib Java probe
на том же25.0.3: Pattern default `\bзавтра\b` → false, UNICODE_CHARACTER_CLASS → true для
«завтра?». Host boundary mismatch подтверждён; JDK/версии/параметры тестов не менялись.
Добавлен CalendarRegex: явные Unicode letter/mark/number/_ zero-width boundaries вместо
platform-default `\b`; применено в TurnPolicy/TemporalExpression/RenameExpression.
Qualifier перед weekday consumed целиком, задаёт current/previous/next calendar week для
всех7 дней; обе грамматические формы. Противоречащие qualifiers → уточнение, не guessing.
Исправлен exact expected exception в invalid-citation test: IllegalStateException и проверка
сообщения о неизвестном source; production cite validation не ослаблялась.
Запуск: assembleDebug + прежние9 classes +CalendarRelativeDateQueriesTest +CalendarRegexTest
+lintDebug, тот же JDK. BUILD SUCCESSFUL34с,11 suites/308tests/0 failures/errors/skips.
Relative210/210; DateRead4/4; Regex3/3; Selection12/12; Source6/6; Temporal18/18;
DocumentMode5/5; Detailed5/5; ChatVM31/31; Store4/4; Pipeline10/10.
APK app/build/outputs/apk/debug/app-debug.apk со встроенным posfix установлен по прямому
запросу на CPH2581 через adb install -r — Success, без очистки данных. Ручная приёмка впереди.
Device/реальный Google Calendar и настоящая Qwen требуют ручной приёмки. Account/API не менялись.

## Дополнение к календарным проверкам по запросу пользователя

Добавлен CalendarRelativeDateQueriesTest:70 parameterized scenarios x3 checks =210 проверок,
затем запущены по разрешению «доделывай и запускай тесты»:210/210 PASS.
Покрытие: позавчера/вчера/сегодня/завтра/послезавтра; эта/прошлая/следующая календарная
неделя;7 дней недели без числа с this/previous/next qualifier, именительный и винительный
падеж («эта пятница»/«в эту пятницу», «прошлая...»/«в прошлую...» и т.д.).
Также unqualified weekdays, границы года/недели, Friday уже прошла, DST23/25h и week167h.
Каждый scenario проверяет: полный consumption temporal words и exact local bounds;
READ full question без QUERY; SEARCH с сохранением quoted title и теми же bounds.
Фиксированный clock/zone, синтетические данные, никакой сети/device. Monday начало недели;
эта/прошлая/следующая пятница = пятница соответствующей календарной недели.
Выявленный пропуск weekday qualifier исправлен; ожидания tests не подгонялись.
Прежние39failures устранены в последнем прогоне (исторический разбор ниже сохранён).

## Последний согласованный UX (заменяет автоматическую маршрутизацию D031)

- Вместо automatic hint — iOS-подобный Switch «Поиск по документам», OFF по умолчанию,
  белый thumb/зелёный checked track. Во время generation менять нельзя.
- OFF: send не вызывает ни doc gateway, ни индекс/классификатор. Обычный chat/calendar path.
  ON: запрос только в documents, без fallback к Calendar. Автоматический LLM route удалён.
- Initial answer короткий (output384), обработанный Qwen, без appended raw chunks.
- Рядом с Copy кнопка «Полный ответ» только у COMPLETE primary short с valid snapshot,
  в режиме ON. Full/обычные/calendar/ошибки не имеют кнопки. После успешного full не повторять;
  failed/interrupted full можно повторить в том же message slot.
- Full — новое сообщение, iterative budgeted processing original snapshot, output до1024
  на порцию, только обработанный Qwen text, источники отдельно. Raw evidence не подставлять
  при ошибке. Unknown citations/generation failure → явная ошибка, не техническая выдача.
- Snapshot включает standalone question/chunks/source refs, не vectors. Сохраняется с
  ConversationStorev3; старые записи совместимы, invalid optional snapshot только отключает
  expansion, не удаляет историю. Поздние реплики/переиндексация не меняют исходный snapshot.
- New chat сбрасывает Switch OFF; rotation сохраняет VM state. Cold launch OFF.
- Storage «Ответ ИИ» тоже short/processed. Raw search cards только после «Найти», не под
  готовым ответом. Existing old raw chat messages не переписываются.

## Диагностика календаря

Код показал: removeSpans(date) оставляет question mark, из-за него trailing phrase cleanup
не удаляет «в календаре», затем QUERY превращает READ в SEARCH и фильтрует реальные события.
Внесено минимальное изменение CalendarTurnPolicy: trim punctuation до cleanup service phrase.
Но calendar regression пока НЕ PASS: переносимость Regex word boundaries требует разбора.

## Последний запуск

Пользователь отдельно разрешил assembleDebug, lintDebug и только9 classes:
DocumentChatModeTest,DetailedDocumentAnswerTest,ChatViewModelTest,ConversationStoreTest,
DocumentPipelineTest,CalendarDateReadRegressionTest,CalendarSelectionRegressionTest,
CalendarSourceSelectionTest,CalendarTemporalExpressionTest.
Команда: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` с9 `--tests` выше и `:app:lintDebug`.
JAVA_HOME=E:\Programs\Android\Android Studio\jbr, фактический java25.0.3.

Результат: BUILD FAILED38с; assembleDebug/packageDebug PASS,95 tests/39 failures.
Из них38 calendar regressions: даже plain Russian dates/commands не распознаются,
plan.operation=null и temporal find=null. Вероятная общая причина — Java25 default `\b`
не-Unicode word boundaries (в старых calendar expressions нет Unicode character-class flag),
в отличие от Android/старых JVM. Нужна явная переносимая Unicode boundary реализация;
не менять JDK/версии/не отключать тесты ради PASS. Android effect пока не подтверждён.
1 DetailedDocumentAnswerTest: invalid citation корректно отклоняется IllegalStateException,
тест ловит только IllegalArgumentException. Исправить конкретное ожидание/message assertion,
не ослаблять validation. 4 других RAG tests PASS; mode/history/chat/pipeline tests PASS.
Lint анализ начал выполняться, финальный lintReport/lintDebug PASS не получен.

## Предыдущая точка остановки (разрешена и закрыта)

На первом FAIL остановились. Затем пользователь разрешил fixes и расширенный запуск:
см. верхний раздел с308 PASS. Device не использовался, APK не установлен.
Календарный API/account/permissions/data не менялись, сеть/Google API не использовались.
