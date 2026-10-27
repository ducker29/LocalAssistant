# Текущее состояние проекта

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
