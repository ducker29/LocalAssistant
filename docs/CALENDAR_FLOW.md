# Calendar: app-owned задача и изменения полей

Текущая реализация 2026-10-27; device-приёмка новой policy ещё НЕ выполнена.
Прежний вариант, где модель выбирала op/new/continue, ручную приёмку не прошёл.

## Ответственность

| Компонент | Роль |
|---|---|
| CalendarSession | Единственная память задачи, объекта, ожидаемых полей, IDs и фазы |
| CalendarTurnPolicy | Stateless разбор текущей реплики/перехода и grounding полей |
| CalendarTurn / FieldPatch | Immutable предложение перехода и типизированные изменения |
| CalendarToolParser / ToolSpec | Только model field-patch protocol; не выбор операции |
| CalendarToolExecutor | Session accept, единый resolve/prepare/confirm/preflight/apply |
| CalendarEventUpdate | Partial update, сохранение дня/длительности, no-op detection |
| CalendarRenameExpression | Старое title/дата отдельно от буквального нового title до разбора реплики |
| CalendarEventSearch / Time | Local token matching и календарные границы |
| CalendarViewModel | Auth/UI-фасад, без собственной копии draft |
| ChatViewModel | Snapshot → модель → проверенный command → UI |
| Authorized/Http gateway | Токен, read retry, REST, pagination, safe diagnostics |
| HttpGoogleTasksGateway / GoogleTaskUpdate | Native Tasks adapter: list-qualified refs, date/notes/status без Event подмены |

## Память

task_id/version/phase связывают active operation и values с конкретной задачей.
State также хранит targets (настоящие API IDs), selector, awaiting fields/mode,
pendingSubject без операции и uncertain-write marker. Второго cache нет.
Snapshot содержит explicit null/[] даже при пустом state; previews явно помечены truncated.
Полные values/IDs остаются в памяти приложения, session JSON в логи не попадает.
Session живёт в текущем процессе, а не восстанавливается из старых реплик при каждом ходе.

## Перед моделью

App policy читает текущую память и реплику. Read-команда разрешает лишь READ/SEARCH;
явный DELETE/UPDATE/CREATE определяет операцию соответствующей новой задачи.
Find+delete-all = новый lookup с write intent, а не ссылка «их» на прежний набор.
Quoted names не интерпретируются как команды; неоднозначные разные write ops уточняются.
Слова/даты из известных форматов извлекаются приложением, без модельных default values.
Это bounded Russian command grammar, не универсальный natural-language parser.

## Протокол модели

APP_OPERATION/APP_TRANSITION уже заданы. Модель читает SESSION и текущую реплику,
затем предлагает только:
```json
{"task_id":0,"version":0,"set":{},"clear":[]}
```
set допускает typed field values, не tool/context/all/ID. Пропущенное = сохранить;
clear — явная очистка, подтверждённая словами пользователя. IDs/цели не меняются патчем.
Предложение старой версии не применяется; app seed приоритетнее, остальные значения
принимаются лишь с доказуемым происхождением и ролью в реплике. В частности модель не
может придумать время/период/новую операцию или перезаписать title на slot-ответе.
Malformed/non-EOG/oversized prompt не запускает произвольный model tool: используются
лишь проверенные app поля; если их мало, задача сохраняется и спрашивает недостающее.
Native count сохраняет ограничение prompt+256+8<=n_ctx. Общий чат не меняет свой budget.

## Переходы состояния

- NEW: новый task, старые op/target/изменения не наследуются.
- Новый самостоятельный subject default kind=EVENT; TASK требует явного «задача».
  Source наследуется только для связанных refs/slots/field edits/temporal reply.
  Сам факт предыдущей работы с Tasks не выбирает API следующего named question.
- CONTINUE: patch к существующему draft того же op; только актуальный task/version.
- TARGETS: новая явно указанная op над сохранёнными IDs, старые proposed fields сброшены.
- NEW_OBJECT: сохранить объект/значения с operation=null; спросить действие.
- GENERAL/CANCEL/Stop/new chat/account: сброс памяти/инвалидация in-flight context.
- Missing fields: COLLECTING + typed awaiting. Ответ с названием/временем не классифицируется
  как новый поиск; самостоятельная новая команда может прервать draft.
- Resolve/choice: фактические IDs, explicit all либо UI выбора; не угадывать цель.
- Confirm: actual target/old→new, полный список выбранных целей.
- Preflight: повторный GET всего набора перед первой записью.
- Execute: PATCH/DELETE без auto-retry; no-op не пишет, первая ошибка останавливает batch.
- Finish: активный op/draft очищен, results остаются контекстом; uncertain result требует
  нового успешного read до дальнейшей записи.

## Сохранённые правила

Пять операций READ/SEARCH/CREATE/UPDATE/DELETE, без шестой CalendarOp.
Один date-group formatter для read и batch outcomes, жирные даты через AnnotatedString.
CalendarTemporalExpression распознаёт полный срок и его span; один parser также для follow-up.
CalendarTime вычисляет exclusive end для относительных дней и calendar/rolling weeks/months.
Calendar previous/current/next — полные недели пн–вс и месяцы, rolling LAST заканчивается
следующей полночью, UPCOMING начинается сегодня. «Через N/ N назад» — одна дата, не период.
Количество без числа уточняется; anchors сохраняют origin day при последующих репликах.
Названия сроков целиком удаляются из query, quantity clarification не меняет название/op.
Local days/месяцы учитывают DST/leap years, предел90 дней явный; time-only сохраняет длительность.
Weekdays без qualifier — текущая пн–вс; previous/next week учитываются до/после weekday.
Absolute day/month/ISO участвуют в intent classification, не только в позднем slot parsing.
Переименование использует title-only query и не берёт новую строку из старого имени/даты;
неоднозначные некавыченные разделители требуют уточнения. Lookup не матчится по notes.
All-day date-only, recurring singleEvents=true/orderBy=startTime.
List pagination <=500; локальный поиск по всем tokens с ё/е и простыми окончаниями.
При превышении cap не выдавать усечённый набор за «все»; batch максимум100.
Batch не транзакция. Multiple CREATE и timed↔all-day conversion отдельно не реализованы.
Auth, зависимости, Manifest и native runtime не менялись в correction-блоке.

Google Tasks подключён отдельным адаптером в той же цепочке. kind EVENT/TASK принадлежит
Session; Task key = listId+taskId, cross-kind targets запрещены. Общие GoogleApiHttp и
GoogleApiAuthorization не дублируют transport/retry; write не повторяется автоматически.
Scope tasks добавлен по явному выбору пользователя, нужны повторные Google OAuth grant/check.
Tasks: catalog/choice, optional due date, notes/title/status update/clear due, delete.
Никаких часов/duration/location API не предоставляет, app не создаёт companion Event.
Без date filter читаются также undated/completed/hidden/assigned задачи; с filter — due date.
UI checkbox/date groups/list names, не all-day claim. Parent delete проверяет subtasks,
assigned delete поясняет эффект на исходный сервис. List CRUD/move/recurrence не реализованы.
Старые pseudo-task Events не мигрируются. Ручная настройка/проверка — GOOGLE_TASKS.md.

## Приёмка

Standalone search после DELETE draft не показывает write confirmation.
CREATE в несколько реплик сохраняет название/дату/op; повтор названия не становится SEARCH.
Find+delete-all корректно обрабатывает query и календарную неделю.
Новый объект не наследует старую op; patch прошлой версии не меняет актуальную задачу.
Проверить прежние read/search/update/delete, форматирование, выбор, отмену и partial failures.
