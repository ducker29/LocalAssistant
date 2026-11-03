# Последовательные изменения разных событий

## Продолжение: pending action и перенос между днями (2026-11-03)

Пользователь сообщил redundant time clarification и потерю действия при коротком ответе;
потребовал хранить текущее действие и поддержать перенос на другую дату/день+время.
Причина: «на N часов» попадало в duration UPDATE branch с ранним CLARIFY до accept/draft.
Новый app-owned plan.clarificationFields передаёт требуемые DATE/TIME в executor: сначала
реально определяется цель/сохраняется request/awaiting, потом задаётся уточнение без записи.
Bare time reply продолжает тот же task ID; неверное число не превращает TIME-await в DATE.
«на N часов» для0..23 без relative markers → clock, explicit duration остаётся duration;
«позже/раньше» не вызывает guessed offset write, требует concrete date/time.
GENERAL ordinary remark больше не очищает CalendarSession; новое Calendar action/CANCEL/
new chat/account reset заменяют/сбрасывают pending action, successful completion завершает.
Это runtime session state, не model memory. Не заявлять восстановление после process death.

From-context query narrowing использует trusted API IDs/results, не ищет moved event снова
в старом дне. Каждый ID GET/revalidate до confirmation и stale check до write сохранены.
Проверены weekday/date-only move с real original time, named/numeric destination+clock,
повторный move того же ID вне original source-day, cancel/explicit new action, draft slots.
Новые CalendarMoveContinuationTest7 и прежние12 suites:460 tests/0failures/errors/skips;
assembleDebug/lintDebug PASS28с. API/личные screenshot данные не использовались.
APK app/build/outputs/apk/debug/app-debug.apk затем установлен по прямому запросу на CPH2581:
adb install -r — Success, данные сохранены. Device QA впереди; live Calendar не читался агентом.

Дата: 2026-11-03. Статус: assembleDebug/453 targeted tests/lintDebug PASS; device-приёмка впереди.

## Задача и установленные причины

Пользователь сообщил: чтение двух событий, подтверждённое изменение первого, затем
запрос ко второму с командой после названия приводит к сгенерированному отчёту без записи.
Предоставленные screenshots прочитаны локально; личные названия/данные в docs/logs не копируются.
/screenshots/ исключён из Git. Тесты используют отдельные synthetic fixtures.

- commands раньше распознавались в начале/после разделителя: subject-first time command
  мог уходить в GENERAL→normal model, которая видела прежний success report в истории.
- Активный targets сужается при выборе/записи. Хранить только его недостаточно для следующего
  named запроса ко второй записи из исходной выдачи и сохранения исходного дня поиска.

## Изменения

- CalendarTurnPolicy: initial conjunction и ограниченный subject-first time-update parser
  вне quoted data; ambiguous/unsupported write hints и elliptical references дают CLARIFY,
  не обычную генерацию. Нельзя превращать команды внутри literal event titles в действия.
- CalendarSession хранит original read resultSet/resultSelector отдельно от active targets.
  Named UPDATE по известной записи без нового source date может восстановить именно этот
  набор через app-owned CalendarTurnPlan.useResultSet. Qwen не передаёт ID/выбор набора.
- Только успешные API results обновляют resultSet; preview/cancel/failure не подтверждают
  изменение. Обычный confirm→validate→API путь сохранён; каждое новое changed событие
  требует отдельного UI подтверждения. Actual no-op честно сообщает, что запись не нужна.
- ChatViewModel backstop не отдаёт неразобранный Calendar write обычной модели; app-owned
  уточнение вместо имитации. Calendar turns помечены calendarRelated и не используются
  как normal model examples; legacy success-format reports также исключаются из prompt.
- ConversationStore v4 сохраняет optional calendarRelated; old history совместима,
  user messages/data не удаляются. Calendar results нельзя «Продолжить» генерацией.
- SYSTEM_PROMPT дополнен правилом, но основной guard — app routing/confirmation/provider,
  а не обещание модели. Live accuracy на телефоне пока не проверена.

## Проверки

Отдельно разрешены assembleDebug, lintDebug и12 классов:
CalendarSequentialEditsTest,CalendarChatSafetyTest,CalendarDateReadRegressionTest,
CalendarRelativeDateQueriesTest,CalendarSelectionRegressionTest,CalendarSourceSelectionTest,
CalendarTemporalExpressionTest,CalendarRegexTest,GoogleTasksWorkflowTest,ChatViewModelTest,
ConversationStoreTest,DocumentChatModeTest. JAVA_HOME=JDK Android Studio.

Первый run453/3fail: synthetic end timestamp сериализовался без секунд; исправлен fixture
на RFC3339 UTC. Второй run453/1fail: test сравнивал UTC hour вместо local; исправлена
только timezone в fake assertion helper. Safety assertions не ослаблялись.
Финальный тот же набор +assembleDebug+lintDebug: BUILD SUCCESSFUL7с,453 tests PASS.

Проверены: два distinct подтверждения/две actual fake API writes, правильные event IDs,
same-name событие на другом дне не выбирается, cancel/retry, provider failure/no success
report, repeated actual no-op, ambiguous clarification, quoted commands as data,
normal Qwen не вызывается без исполнителя для Calendar write, legacy prompt exclusion,
metadata persistence, прежние Calendar/Tasks/chat/document-mode регрессии.

APK app/build/outputs/apk/debug/app-debug.apk установлен по прямому запросу на CPH2581:
adb install -r — Success, данные сохранены. Google API/личные события не читались/не менялись.
Следующий шаг — ручной повтор
аналогичного диалога со SwitchOFF. Не выдавать старые сохранённые сообщения за новые API results.
