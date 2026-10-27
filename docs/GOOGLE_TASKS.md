# Google Tasks: публичный API

Реализован вариант 1, выбранный пользователем. Runtime-проверка после новой сборки впереди.

## Настройка

В том же Google Cloud-проекте, где OAuth client приложения:
1. APIs & Services → Library → Google Tasks API (`tasks.googleapis.com`) → Enable.
2. Google Auth Platform → Data Access → добавить `https://www.googleapis.com/auth/tasks`.
3. Существующий `https://www.googleapis.com/auth/calendar.events` оставить.
Пользователь сообщил, что пункты 1–2 выполнены. Это не заменяет согласие конкретного аккаунта.

После установки APK: настройки приложения → Google-аккаунт → «Проверить» → при запросе
повторного подключения «Подключить», выбрать аккаунт и подтвердить Calendar + Tasks.
Чистить app data, удалять модель или переустанавливать с uninstall не нужно.

## Возможности

- «Покажи списки задач» — каталог, «Покажи список задач»/«Мои задачи» — сами задачи.
- Чтение всех доступных списков или «из списка „Личные“», поиск по словам/датам/статусу.
- Создание title/notes и optional date; при нескольких списках явный UI choice.
- Rename/notes/date update, clear date, «Отметь её выполненной», «Верни её к выполнению».
- Delete после подтверждения; родитель с subtasks не удаляется незаметным cascade.
- Задачи без даты видны в all-dates, но не в заданном периоде. Completed видны с ☑,
  needsAction с ☐, можно запросить status filter. Period относится к due date.

«Создай задачу Проверка завтра» создаёт Task, «Создай событие Проверка завтра в 16» — Event.
Новый самостоятельный запрос без слова «задача» относится к Event даже после Task-поиска.
Только ссылки на выбранную запись и slot/temporal clarification сохраняют реальный kind
(«Отметь её выполненной», «А завтра?»), а не меняют тип уже выбранного объекта.
Quoted слова «задачи» в literal Event title не меняют источник. Источники не смешиваются
в одном неявном наборе, IDs Task всегда связаны со списком и kind.

## Ограничения

due — scheduled date, не нативное время и не timestamp завершения. API отбрасывает time;
app не переводит due через Instant/часовой пояс и не называет Task «событием на весь день».
Запрос clock/duration требует убрать время; остальные поля draft сохраняются.
Дата может отсутствовать; Task не создаёт скрытый Event или локальное напоминание.
Управление самими списками, move между списками, создание subtasks/recurrence не добавлены.
Назначенные задачи имеют ограничения своего источника; delete показывает, что затронет
оригинал Docs/Chat. Parent guard и refetch не превращают batch в транзакцию.
Catalog до100, result до500, write batch до100, date range до90 дней — overflow уточняется.

Scope `tasks` — чтение/изменение/создание/удаление, `tasks.readonly` для этого недостаточен.
Официальный контракт: https://developers.google.com/workspace/tasks/reference/rest/v1/tasks
Диагностика: CalendarAssistantDebug, только categories/status/route templates, без task text.
