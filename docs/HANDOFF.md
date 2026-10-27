# Передача контекста проекта

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
