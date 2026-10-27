# Передача контекста проекта

## Новый запрос: multi-turn контекст, draft creation и batch writes (2026-10-27)

Пользователь принял single UPDATE/DELETE. Последний capture остановлен с проверкой PID,
stderr пуст. Подтверждено: PATCH200/Ok и DELETE204/Ok после lookup/confirmation/refetch.
401 перед первым search был успешно восстановлен через token invalidate/retry, не чинить auth.

Новые сценарии пользователя (обезличенно): найти события, следующей репликой изменить/
удалить их; найти все совпадения за текущую неделю и удалить; начать CREATE без времени,
следующей репликой дополнить время/подтвердить число месяца без потери title/op.
Trace подтверждает: SEARCH defaults30 → DELETE с заново заполненными query/source_date
→ none; SEARCH days7 завершился сам; CREATE title/date/duration без time → Error;
следующий slot-like ответ ошибочно стал DELETE/source_date/source_time → none.
Точные значения date/query/time в trace скрыты, не заявлять, что восстановлены из него.

Диагноз: recentEvents хранится executor в памяти, но нет active-task state/drafts/missing
slots, planner видит лишь последние user turns, не app session. Query re-search раньше
cached IDs заново задаёт диапазон; текущий prompt явно отказывает в multiple writes.
Поэтому исправление не сводить к увеличению history или примерам prompt.

Предлагаемый следующий scope (ЕЩЁ НЕ СОГЛАСОВАН): typed CalendarConversation state
с active intent, selected result set/query/range, CREATE/UPDATE draft, missing fields;
контролируемое merge уточнений без смены op; explicit continue/new-task/leave-calendar
переходы, сброс при topic change/отмене/new-chat/account change. Существующие 5 CalendarOp
сохранить. Для bulk: all_matching flag/selection, actual week bounds, pagination,
review полного списка, UI confirmation, поэлементные outcomes без silent partial success
и без авто-повтора неопределённых записей. Укладывать typed prompt context в 2048 tokens.
Новая permission/зависимость не нужна. Значительное изменение state/schema/UI — получить
согласие до реализации. Автотесты по-прежнему запрещены без отдельного разрешения.

После одобрения/сборки отдельно получить разрешение на install/capture, затем сразу
сообщать готовность и ждать «готово». Сейчас device install/build в новом scope не было.

## UPDATE/DELETE: вариант 2 реализован, требуется runtime-приёмка (2026-10-27)

Пользователь подтвердил вариант app-side resolver и сообщил пример переноса завтрашнего
события с 10:00 на 08:00. Конкретное личное название не сохранять в документах.
Предыдущая трассировка: READ/SEARCH200 и CREATE/POST200 работали; DELETE без ID ошибался
после подтверждения, два DELETE с ID получали GET404. Самих HTTP DELETE/UPDATE не было.

Новые изменения:
- CalendarToolRequest.kt: optional sourceDate/sourceTime и признак allDaySpecified;
  parser: string/boolean/int types, unknown-field rejection и ограничения длин.
- CalendarToolSpec.kt: UPDATE/DELETE несут query и исходные date/time, приложение ищет
  само; UPDATE date/time — destination, duration не выдумывать; обезличенный пример переноса.
- CalendarToolExecutor.kt: recent API candidates in-memory, неизвестные model IDs не
  служат целью; search/source day/last results, фильтр исходного времени; multiple →
  target selection deferred с сохранённым write request; fetch → prepare → confirm →
  refetch/compare → PATCH/DELETE. Отмена/finally чистят UI state, contextGeneration
  блокирует stale candidates после смены подключения. Sync UPDATE/DELETE не подтверждаются.
- Новый CalendarEventUpdate.kt: time-only оставляет день и исходную длительность;
  partial update, timezone/DST validation, all-day date move с сохранением числа дней;
  смена all-day/timed возвращает уточнение, не конвертирует автоматически.
- HttpCalendarGateway.kt: PUT заменён PATCH, JSON содержит лишь заданные поля.
- CalendarViewModel.kt + ChatScreen.kt: выбор реальных candidates, подробный confirmation
  actual target / old→new, без новых зависимостей. ChatViewModel stop/new-chat отменяют
  pending interactions, новый чат/подключение очищают API context.
- CalendarAssistantDebug.kt: новые поля редактируются; только категории resolve,
  none/single/multiple, validation и confirmed_plan без event text/ID.

Проверки: финальный assembleDebug SUCCESS (1 с), git diff --check успешно.
Первый compile показал optString(null) warning — исправлен typed safe cast;
существующий LocalLifecycleOwner warning не относится к этому блоку.
Unit/UI tests и lint не запускались (действует запрет автоматических тестов).
После отдельного разрешения пользователя OnePlus обнаружен, новая сборка установлена
через install -r — Success, данные сохранены. Commit/push не выполнялись.
Следующая операция — capture; после запуска сразу сообщить «можно проверять» и
прекратить работу до «готово».

APK: `E:\Programs\Android\AndroidStudioProjects\LocalAssistant\app\build\outputs\apk\debug\app-debug.apk`.
Приёмка: перенос выбранного события 10→08 с той же длительностью и днём, cancel не пишет;
multiple показывает выбор; DELETE показывает точное имя/время и идёт только после confirm.
Ожидаемый trace: UPDATE/DELETE → target_resolution → candidates → GET200 →
awaiting_confirmation → confirmation=true → GET200 → confirmed_plan → PATCH200/DELETE204.
Модель и Android HTTP PATCH ещё не проверялись в runtime; не утверждать успешность.
Scope не включает batch writes, полную model/tool петлю и pagination. Старый baseline
commit 2db2b9f сохранён; date-format и новые resolver-правки остаются незакоммиченными.

## Актуальный блок: даты/поиск/группы установлены, ожидается проверка (2026-10-27)

Пользователь попросил сначала commit baseline, затем одобрил блок дат/поиска/групп.
Создан `2db2b9f fix(calendar): restore event access and trace tool requests`, 10 файлов;
точный список/сообщение показаны перед коммитом. AGENTS.md и untracked prompt/review/log
не включены. Новый блок не коммитить автоматически.

Новые изменения:
- CalendarEvent.kt: zone-aware start/time range, date-only all-day, безопасный fallback
  для отсутствующего/невалидного времени; summary больше не падает на all-day.
- CalendarTime.kt: QueryRange с границами целых local days; date без days → 1 день,
  иначе READ=7/SEARCH=30; days ограничен 1..90, DST через LocalDate.atStartOfDay.
- CalendarToolExecutor.kt: общий read/search period, проверка query/дат, заголовок по
  периоду; все полученные события сортируются и группируются по start date;
  markdown-подобные date heading строки для UI, больше нет take(10).
- HttpCalendarGateway.kt: singleEvents=true/orderBy=startTime для recurring occurrences
  и сортировки страницы. Лимит страницы 25 сохранён, pagination ещё нет.
- CalendarToolSpec.kt + ChatViewModel.kt: date/days semantics, явный SEARCH tomorrow
  days=1, приоритет текущего вопроса; history/current помечены в одном user turn.
- ChatScreen.kt: узкий date-heading AnnotatedString renderer, жирные даты без новой
  зависимости; user messages plain; формат сохранённых сообщений не менялся.

Проверки: assembleDebug SUCCESS (3 с); diff --check — успешно. Unit/UI tests и lint
не запускались по текущему scope. Существующий warning LocalLifecycleOwner deprecated.
USB-блокер снят: пользователь подключил OnePlus, adb видит CPH2581;
новая сборка установлена через install -r — Success, данные сохранены.
APK: `E:\Programs\Android\AndroidStudioProjects\LocalAssistant\app\build\outputs\apk\debug\app-debug.apk`.
Следующая операция: capture только CalendarAssistantDebug и СРАЗУ остановиться/сообщить готовность.
Проверка пользователем: недельный список по жирным датам и времени; следующий вопрос
о выбранном типе событий завтра → SEARCH/date tomorrow/days1, корректный период в UI.
Поведение модели/Compose на устройстве ещё не проверено. Не утверждать, что всё исправлено.
Baseline чтение подтверждено предыдущей сборкой HTTP200/Ok и пользователем.
Дальнейший scope: pagination, диапазоны с end_date, multi-step tool loop, batch writes.

## Последняя установленная версия: optional fields + exception stage (2026-10-27)

Пользователь одобрил точечный fix после 200/ResponseOrRequestFailure.
`CalendarEvent.kt`: `optString(location/description, "").takeIf { it.isNotBlank() }`
вместо null fallback; отсутствие/пустота этих полей сохраняются как null в модели.
`HttpCalendarGateway.kt`: фиксированный stage и имя exception class в трёх catch-логах,
без message/stack/body/event text. Маппинг ошибок и all-day formatter пока не менялись.
Финальный assembleDebug — SUCCESS (1 с), compile без новых предупреждений;
OnePlus install -r — Success. Автотесты/lint не запускались.
Следующая операция — capture CalendarAssistantDebug и немедленно сообщить «можно проверять».
Затем ждать «готово», остановить именно capture-процесс и прочитать temp log.
Пока не заявлять, что отсутствие optional fields было доказанной причиной: ожидаем runtime.

## Последнее воспроизведение: API вернул 200, сломалась обработка (2026-10-27)

Одобренный route fix выполнен в `HttpCalendarGateway.kt`: base `/calendar/v3`,
GET list/search/get используют `/calendars/primary/events`, route templates согласованы.
Сборка `.\gradlew.bat :app:assembleDebug` — SUCCESS (1 с); diff check успешен;
OnePlus install -r — Success, данные не очищались. Автотесты/lint не запускались.
Захват завершён после «готово» пользователя, stderr пуст.

Факт runtime: READ days=7 → facade authorization=Ok → executor connected=true →
GET `https://www.googleapis.com/calendar/v3/calendars/primary/events` → 200 →
ResponseOrRequestFailure → tool_result=Error. UI «Нет сети» является неверным
обобщением catch Exception в gateway, не доказательством отсутствия сети.

Код проверен: `CalendarJson.parseEventList` вызывает `CalendarEvent.fromItem`, где
`item.optString("location", null).takeIf { it.isNotBlank() }` и аналогично description
могут вызвать NPE для отсутствующих optional fields. Это наиболее конкретный кандидат,
но exception class/stack в безопасной трассировке пока не собраны.
Также `CalendarEvent.formatInstant` всегда Instant.parse, в том числе для all-day date;
это другой известный риск, пока не подтверждён этим логом (gateway уже падает до formatter).

Следующий предложенный scope (ждать согласия): nullable-safe optional-field parsing в
`CalendarEvent.kt`; safe error class/stage diagnostics в `HttpCalendarGateway.kt`
без exception message, event text или response body. Затем assembleDebug, install -r,
capture и сразу сообщить пользователю о готовности — после запуска capture не продолжать
фоновую работу/документы до воспроизведения. Факт доступа есть, видимость списка ещё нет.
Commit/push, массовая переработка Calendar и автотесты по-прежнему не разрешены.

## Последняя проверка устройства: READ/SEARCH получили HTTP 404 (2026-10-27)

Пользователь попросил сначала проверить базовое чтение календаря. Новая planner-сборка
установлена на OnePlus через `install -r` — Success. Фильтр CalendarAssistantDebug
запущен в temp вне репозитория; после двух запросов прочитан, stderr пуст, capture
процесс остановлен после проверки PID/аргументов. Данные/события не изменялись.

Оба хода подтверждают `connection_state_in_prompt=true`, EOG, parser OK (READ, SEARCH),
authorization=Ok, executor connected=true, затем неверный GET `/calendar/v3/users/me/events`
и HTTP 404/NotFound. UI «Событие не найдено» — отображение HTTP-ошибки, не пустой список.
Первый READ date есть, executor date_parameter_applied=false; второй SEARCH date=null.
Planner fix достиг цели маршрутизации, успех чтения пока не подтверждён.

Следующий минимальный план (ожидает согласия): только `HttpCalendarGateway.kt`, исправить
base на `https://www.googleapis.com/calendar/v3`, list/search на
`/calendars/primary/events?...`, get на `/calendars/primary/events/{id}`;
согласовать safe route templates в диагностике. Пути create/update/delete уже начинаются
с `/calendars/...`; при новой базе не добавлять второй префикс. Не вызывать записи при
проверке. Сборка assembleDebug, обновление через install -r и новый capture только в
одобренном scope. Критерий — GET корректного endpoint, HTTP 200/error=None, tool_result=Ok
и сопоставление списка пользователем. Не обещать корректность «завтра» этим fix.
Автотесты по-прежнему не запускать без снятия ограничения. Commit/push не разрешены.

## Актуальный блок: planner input исправлен (2026-10-27)

Пользователь одобрил первый fix и потребовал учитывать все 5 операций из
`CALENDAR_IMPLEMENT_PROMPT.md`, одиночные/множественные дни и события. Документ прочитан.
Выполнен только ранее согласованный planner-блок, не полная переделка Calendar:
- `CalendarToolSpec.kt`: dynamic state category (exhaustive when без payload), device-local
  дата/время/zone, все 5 public→internal operation mappings, примеры READ/SEARCH/CREATE,
  UPDATE/DELETE через поиск при неизвестном ID, запрет выдумывать значения и выбирать
  произвольное событие. Множественная запись пока ведёт на уточнение, а не batch execution.
- `ChatViewModel.kt`: один snapshot state передаётся в prompt и trace; только последние
  3 user-сообщения вместо свободных assistant-ответов. Основная chat history сохранена.
- Логи первого диагностического воспроизведения остаются baseline старой сборки;
  новый planner должен логировать `connection_state_in_prompt=true`.

Проверено: `.\gradlew.bat :app:assembleDebug` — успешно, 2 с; `git diff --check` — успешно.
Автотесты не запускались согласно текущему ограничению; lint не запускался.
APK прежний путь, файл обновлён сборкой: `app/build/outputs/apk/debug/app-debug.apk`.
Эта версия на устройство ещё не установлена, runtime-успех не заявлять.

Необходимый следующий scope для согласования: schema/parser для диапазона и нескольких
операций при сохранении 5 типов команд; app-side строгая валидация дат/обязательных полей
и известных event IDs; executor с local-day boundaries, поиском/выбором кандидатов,
подтверждением конкретных действий и без silent batch writes; bounded multi-step loop
с structured tool results для модели, без повторного выполнения записей; исправление
подтверждённых HTTP route проблем. Учесть pagination/явное ограничение результатов.
Сейчас READ не применяет date, SEARCH требует query и не применяет date,
CREATE может подставлять default time/duration; prompt эти дефекты не исправляет.
Обсудить focused unit tests для этого расширенного блока: ранее пользователь запретил
автоматический запуск тестов. Установка/повторный захват — только в согласованном scope.

## Последний runtime checkpoint (2026-10-27)

После диагностической сборки пользователь явно разрешил обновление приложения и захват
логов на OnePlus CPH2581. `adb` отсутствует в PATH; использован SDK
`%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`. `install -r` — Success.
Захват только `CalendarAssistantDebug:I *:S`, без очистки logcat; после «готово»
процесс захвата остановлен (PID проверен по имени и аргументам). stderr пуст.
Данные приложения не очищались, commit/push не выполнялись.

Результат одного хода: Connected → EOG → raw `{"tool":"NONE"}` (15 символов) →
unsupported_NONE → normal_chat COMPLETE. Вызова CalendarToolExecutor и HTTP не было.
Подтверждена неверная маршрутизация planner, а не поломка JSON/ошибка Google API.
Почему модель выбрала NONE (state отсутствует в prompt, влияние history и др.) — ещё гипотезы.

Минимальный план для согласования: изменить только planner prompt/input
(`CalendarToolSpec.kt`, `ChatViewModel.kt`): безопасный connection state без email,
явное правило READ для запросов просмотра расписания и короткие примеры; исключить
прежние свободные assistant-ответы об отсутствии доступа из planner input, сохранив
обычную историю чата и данные для разрешения ссылок на события при необходимости.
Никаких исправлений пока не внесено. Критерий первого блока — parser OK op=READ,
executor и HTTP достигнуты; это ещё не приёмка правильного ответа за завтра.
После одобрения отдельно согласовать проверки/повторное обновление устройства.
Endpoint, обработка date и возврат tool result модели остаются отдельными вопросами.

## Актуальная контрольная точка: Calendar diagnostics (2026-10-27)

Пользователь сообщил, что подключение Google-аккаунта работает, но ассистент отказывает
в чтении календаря. Прочитан `docs/CALENDAR_CHAIN_REVIEW.md`, выводы сверены с кодом.
Текущая задача — только диагностика; исправлений бизнес-логики не согласовано.

Изменения в `app/src/main/java/com/example/localassistant/`:
- Новый `calendar/CalendarAssistantDebug.kt`: debug-only logger, coroutine-local ID,
  fail-closed маскирование raw (сохраняются синтаксис и словарь схемы), безопасные
  параметры и фиксированные категории parser rejection.
- `chat/ChatViewModel.kt`: заменены небезопасные логи текста запроса/raw/параметров;
  state/input shape, termination, parser, dispatch, final result, returned_to_model=false.
- `calendar/CalendarViewModel.kt`: категория результата ensureToken и state без payload.
- `calendar/CalendarToolExecutor.kt`: connected, ветка READ/SEARCH, подтверждение записи;
  вместо query/date/exception message — безопасные категории.
- `calendar/AuthorizedCalendarGateway.kt`: результат авторизации, 401/retry без токенов.
- `calendar/HttpCalendarGateway.kt`: method, route template без query/ID, status/error;
  удалены логи exception/error messages. Тела и заголовки не логируются.

Поведение сохранено: статический planner prompt без connection state, строгий JSONObject,
NONE отклоняется, fallback в normal chat, READ использует 7 дней и игнорирует date,
tool result становится UI-ответом без второго вызова модели. Эти наблюдения не являются
доказанной runtime-причиной отказа. Утверждение review о корректности endpoint не
подтверждено: фактический GET `/calendar/v3/users/me/events` требует проверки по HTTP-логу.

Проверки: `:app:tasks --all` — задача существует; `git diff --check` — успешно;
`.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL, 4 с. Имеется warning об уже
существующем deprecated `TRIM_MEMORY_RUNNING_LOW`. Unit/UI tests не запускались по
прямому ограничению пользователя; lint не запускался (согласован только assembleDebug).
APK: `E:\Programs\Android\AndroidStudioProjects\LocalAssistant\app\build\outputs\apk\debug\app-debug.apk`.
Установка, подключение/проверка устройства, adb, commit/push не выполнялись.

Следующая операция — только со стороны пользователя: установить APK с сохранением данных,
убедиться в подключении аккаунта, запустить захват логов и отправить один запрос:
«Что у меня завтра в календаре?». Дождаться итогового ответа и остановить захват Ctrl+C.
Команда ниже предназначена пользователю и агентом НЕ выполнялась:
```powershell
adb logcat -v threadtime 'CalendarAssistantDebug:I' '*:S'
```
Android Studio Logcat: `package:com.example.localassistant tag:CalendarAssistantDebug`.
Не очищать историю/данные/логи, не отключать аккаунт ради этого сценария.

Как читать логи с одним `[ID]`:
- `planner state=Connected, connection_state_in_prompt=false` — state доступен приложению,
  но не добавлен в planner prompt; это не подтверждение валидности токена.
- `planner raw length=...` — реальная длина и маскированная структура ответа.
- `parser REJECTED: reason=not_json_object|unsupported_NONE|missing_tool|unknown_operation|days_out_of_range`
  — причина fallback; executor/HTTP для этого ID не ожидаются.
- `parser OK: op=READ, ...` → `facade authorization=Ok` → `executor: op=READ, connected=true`
  — пройдены парсинг и локальные проверки доступа.
- `authorized gateway: authorization=Ok` → `HTTP method=GET, endpoint=..., status=...`
  — достигнут HTTP gateway; `error=None` означает также успешный разбор тела ответа.
- `error=Unauthorized|Forbidden|Configuration|NotFound|RateLimited|Server|Network|ResponseOrRequestFailure`
  — безопасная категория; 401 дополнительно виден как invalidate/retry_authorization.
- `final tool_result=Ok|Error|NotConnected, returned_to_model=false, destination=UI`
  — итог tool и отсутствие передачи его модели в этом ходе.
- `destination=normal_chat` → `final outcome=normal_chat, status=...` — сработал fallback.

После получения логов сначала предложить минимальный fix plan; реализация только после
согласования. Исходные пользовательские изменения `AGENTS.md`, незарегистрированные
review/prompt/log-файлы сохранены; `calendar-auth.log` не читался и не изменялся.

## Состояние на 2026-10-27 — явная проверка Google-аккаунта (ждёт ручной проверки)

Где остановились: исправлено «бесконечное Checking». Две причины, обе устранены:
1. `CalendarAuthManager.initialState()` ставил `Checking` при наличии сохранённого
   токена — теперь восстанавливает `Connected(email)` / `NeedsReconnect` /
   `Disconnected` из локальных данных (`restoredStateFromRetainedData()`).
2. `ChatScreen` запускал `onSettingsOpened()` через `LaunchedEffect` при открытии
   вкладки «Календарь» — убрано. Проверка только по кнопке «Проверить».

Изменения (все в `app/src/main/java/com/example/localassistant/`):
- `calendar/CalendarAuthManager.kt`: `initialState()` → restored-state;
  `cancelCheck()` (generation++ отбрасывает поздние колбэки); `checkConnection()`
  ловит `CancellationException` отдельно от таймаута, оба случая восстанавливают
  состояние из локальных данных (отмена ≠ ошибка авторизации).
- `calendar/CalendarViewModel.kt`: `onSettingsOpened()` заменён на `checkConnection()`
  + `cancelCheck()` (отменяет job и вызывает `authManager.cancelCheck()`).
- `chat/ChatScreen.kt`: удалён авто-LaunchedEffect; новые параметры
  `onCalendarCheck` / `onCalendarCancelCheck`, проброшены в `SettingsDialog`.
- `settings/SettingsScreen.kt`: заголовок карточки «Google-аккаунт»; ветка
  `Checking` — индикатор + disabled «Проверить» + enabled «Отменить проверку»;
  `CalendarSettingsCard` получил `onCancelCheck`.
- `MainActivity.kt`: `onCalendarCheck = { calendarViewModel.checkConnection() }`,
  `onCalendarCancelCheck = { calendarViewModel.cancelCheck() }`.

Проверено: `.\gradlew.bat :app:assembleDebug` — успешно (4 с). На устройство
**не устанавливалось**, adb-команд не запускалось, тестов не добавлялось.
Коммит не создавался.

Следующий шаг (пользователь): установить APK и вручную проверить:
1. Настройки → Календарь — карточка «Google-аккаунт», автотеста при открытии нет.
2. Кнопка «Проверить» → состояние `Checking` с disabled «Проверить» и
   «Отменить проверку»; «Отменить проверку» возвращает рабочее состояние
   (Connected/NeedsReconnect/Disconnected по сохранённым данным), не зависает.
3. Остальные ветки: `Disconnected` — «Подключить Google-аккаунт»;
   `Connected` — email + «Проверить» + «Отключить»; `NeedsReconnect`/`Unavailable`/
   `Error` — «Подключить Google-аккаунт» + «Проверить».

## Состояние на 2026-10-27 — Calendar auth lifecycle

Где остановились: после первого ручного прогона лог показал
`disconnect: tokenCleared=true, googleRevoked=false`. Причина: Authorization API не
сообщает email аккаунта (`email=unavailable`), поэтому `revokeAccess` не вызывался.
Добавлен системный `AccountPicker` без новых разрешений: выбранный email сохраняется,
`AuthorizationRequest.setAccount(...)` привязывает запрос к аккаунту, `disconnect`
отзывает именно его. Аккаунт теперь обязателен: старые сессии без email переводятся в
`NeedsReconnect` и требуют повторного выбора.

Локальные проверки после правки: `:app:testDebugUnitTest` (76, 0 failures),
`:app:assembleDebug`, `:app:lintDebug` — успешно. Новая сборка на телефон **не
устанавливалась**: нужен `adb -s 2d46124b install -r` и ручной прогон:
1. Настройки → Календарь → «Подключить» → выбрать Google-аккаунт → при необходимости
   consent → ожидается «Подключён».
2. «Отключить» → в логе ожидается `googleRevoked=true`.
3. «Проверить» → ожидается `checkConnection: skipped (disabled or user-disconnected)`,
   статус остаётся «Не подключён».
4. «Подключить» снова → выбрать аккаунт → «Подключён».

Изменено:
- `calendar/CalendarAuthManager.kt`: `Checking/Connected/NeedsReconnect/Unavailable/Error`,
  `ensureToken` (silent `authorize`, без UI), `checkConnection`, `connect` через
  Activity-scoped client, `handleConsentResult`, `invalidateRejectedToken` (`clearToken`),
  `disconnect` (`clearToken` + best-effort `revokeAccess`), generation против late-callback.
- `calendar/CalendarTokenStore.kt`: schema v3, без выдуманного `expiresAt`; `enabled`,
  `user_disconnected`.
- `calendar/AuthorizedCalendarGateway.kt` (новый): единый токен перед вызовом; 401 →
  инвалидация и один повтор только для list/search/get; записи не повторяются.
- `calendar/HttpCalendarGateway.kt`: типизированные `CalendarApiException`; 401/403
  (rate limit / configuration / forbidden / server / network).
- `calendar/CalendarGateway.kt`: `CalendarApiError.Configuration`, payload-типы, исключения.
- `calendar/CalendarToolExecutor.kt`: `mapFailure(t, isWrite)`; ошибка GET при update/delete
  больше не выдаётся за «не найдено».
- `calendar/CalendarViewModel.kt`: `ConsentRequest(id, …)`, реальные `statusNote` /
  `calendarSettingsPrompt`, `onSettingsOpened`, сброс `isAuthorizing` в `finally`.
- `MainActivity.kt`, `ChatScreen.kt`, `SettingsScreen.kt`, `LocalAssistantApp.kt`: запуск
  consent, проверка при открытии настроек, карточки состояний, единое DI.
- `gradle/libs.versions.toml`: `play-services-auth` 21.3.0 → 21.4.0 (разрешено
  пользователем; в 21.3.0 нужных классов нет).
- `app/src/test/.../calendar/AuthorizedCalendarGatewayTest.kt`: 5 тестов политики retry.

Проверено: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
`:app:lintDebug` — успешно. На телефоне ещё нет.

Следующий шаг: разрешение на установку, затем сценарий:
1. Открыть настройки → Календарь: статус не «Подключён» при отсутствии валидного grant.
2. «Подключить» → consent → «Подключён».
3. «Отключить» → «Подключить» → consent/прямой токен → снова «Подключён».
4. Повторить disconnect/reconnect 2–3 раза; проверить, что запоздавший consent не
   возвращает «Подключён».
5. В чате: «Что у меня завтра в календаре?» — отдельная известная проблема планировщика.

Не делать без разрешения: commit, push, установка APK, удаление данных/моделей.

## Состояние на 2026-10-26 — продолжение ответа и стабильный автоскролл

Реализовано продолжение `TRUNCATED`-ответа и переработан автоскролл. Изменения
**не закоммичены** (по требованию пользователя), remote не настроен.

- `llama_jni.cpp` / `LlmNative.kt`: `assistantPrefix` в `nativeGenerate` и
  `nativeCountPromptTokens`; шаблон рендерится с `add_generation_prompt=true`, затем
  префикс дописывается к промпту — модель продолжает тот же ход.
- `LlmEngine.kt` / `LlamaEngine.kt`: параметр `assistantPrefix = ""`.
- `ChatViewModel.kt`: `continueGeneration(messageId)`; новых сообщений/реплик нет,
  в промпт идут только `COMPLETE`-ходы с `id < targetId`, старые отбрасываются.
  Планировщик резервирует только `MIN_OUTPUT_TOKENS` (иначе длинный префикс
  отклоняется), фактический бюджет — прежняя формула `min(1024, n_ctx − prompt − 8)`.
  Статусы: `CANCELLED→INTERRUPTED`, `BUDGET→TRUNCATED`, `CONTEXT→TRUNCATED`+ошибка,
  `ERROR→FAILED`+ошибка, `EOG→COMPLETE`. `ChatUiState.continuationMessageId`.
- `ChatScreen.kt`: кнопка «Продолжить» / «Продолжаем…» (disabled) только у
  `TRUNCATED`; `autoFollow` отключается на `DragInteraction.Start`, включается у низа
  и не отключается ростом текста; ключи эффекта включают статус последнего сообщения,
  чтобы плашка и кнопки не оставались за экраном.
- `ChatMessage.kt`: `ChatMessage.isContinuable`.
- `ChatViewModelTest.kt`: +8 тестов (префикс не дублируется, повторный `BUDGET`,
  `EOG`, отмена, нехватка контекста, длинный префикс, формула бюджета, `isContinuable`).

Проверено вручную на `emulator-5554` (`install -r`): ответ 1024 токена → `TRUNCATED`
+ кнопка; продолжение дописало в то же сообщение (`prefix=4884 bytes`, бюджет 933) и
завершилось EOG/`COMPLETE`; прокрутка вверх во время стрима не сбивается, возврат к
низу возобновляет фоллоу; после перезапуска история (6 сообщений) и продолженный текст
на месте, `im_end`/`im_start` в файле нет. Юнит-тесты: 71, 0 failures; lint — успешно.

**Не проверено на CPH2581 (`2d46124b`)** — устройство не было подключено. APK:
`app\build\outputs\apk\debug\app-debug.apk`, 42 892 481 Б, SHA-256
`C7FCE38A6B4214C67E4CD78E7DA32F816DC3B1EE4A250E7397E35C9DC64E088F`.

## Состояние на 2026-10-26 — M2 stability fix (CPH2581)

Исправлены два дефекта, воспроизведённые на реальном OnePlus CPH2581 (`2d46124b`):
утечка `<|im_end|>` в ответ/историю и SIGABRT на `GGML_ASSERT(n_tokens_all <= n_batch)`
при промпте > 512 токенов.

- `llama_jni.cpp`: EOG-токен больше не рендерится; куски идут с `special=false`;
  промпт префиллится чанками по `n_batch`; переполнение — `CONTEXTEXHAUSTED|…`.
- `SpecialTokenFilter.kt`: фильтр спецтокенов на границе стриминга + `strip()` для
  статического текста; `ConversationStore.load` очищает уже сохранённые сообщения.
- `LlamaEngine.parseTermination`: `substringBefore('|', "")` ошибочно превращал
  `BUDGET`/`CANCELLED` в `EOG`; теперь коды без `|` сохраняются.

Проверено на телефоне: длинный ответ 1024 токена → `TRUNCATED` + плашка; короткий →
EOG/`COMPLETE`; отмена → `INTERRUPTED`; повторные отправки стабильны; история (26
сообщений) пережила перезапуск; `<|im_end|>` нет в UI и файле; «+» очищает чат.
Юнит-тесты: 63, 0 failures; lint — успешно. При установке использовался `install -r`,
данные/модель/история сохранены. Remote не настроен, пуш не выполнялся.

## Состояние на 2026-10-26
**Phase 1 коммит создан локально** на `master`: `7b1d27a` —
`feat: verified speech input, transcription, and chat persistence`.
В коммите: исходники `:app`, юнит-тесты, Gradle, `docs/`, и вендоренные
`third_party/llama.cpp` (v0.7.0) + `third_party/whisper.cpp` (2049 файлов) —
вендоренные источники обязательны для воспроизводимых локальных сборок.
Исключены: `whisper/` (CMake-модуль сборки), `.idea/`, `diagnostics/`, а также
`models/`, `*.gguf.part`, кэши, секреты. **Remote не настроен, коммит не пушен**
(по требованию). Рабочее дерево сохранено: в нём остались незакоммиченные
instrumented-тесты `app/src/androidTest/.../asr/{WhisperEngineTest,
WhisperCancellationTest,NativeHandoffTest}.kt` (не входят в Phase 1).

## Состояние на 2026-10-25
M3 (STT через whisper.cpp) **реализована в исходнике и проверена на эмуляторе**.
Модуль `:whisper` (NDK-сборка вендоренного whisper.cpp) подключён к `:app`.
Реализован JNI-мост `whisper_jni.cpp` с отменой через `AtomicBoolean` и `abort_callback`
(троттлинг убран). Реализованы: запись 16 кГц (fallback 48/44.1), лимит 60 с, черновик
при уходе в фон, вставка в поле ввода без автоотправки, скачивание/импорт/удаление
модели, фильтр неречевых меток, отмена записи и распознавания.

Проверено автоматизированными тестами на эмуляторе: транскрипция фикстуры
(`WhisperEngineTest`), Qwen→Whisper→Qwen (`NativeHandoffTest`), отмена
(`WhisperCancellationTest` — 3/3 PASS: 4899/5328/4888 мс), юнит-тесты M3
(13 тестов, 0 failures, 2026-10-24 20:34:56), lint (0 errors, 22 warnings,
2026-10-24 20:39:02). `assembleDebug` — успешно (APK 2026-10-25 15:23:52).

Проверено пользователем вручную (эмулятор): запись реального голоса через микрофон
хоста, транскрипция, вставка распознанного текста в редактируемый черновик.

Скачивание модели через UI — проверено проверкой агента/рантайма (аудит, §5).

Не проверялось: явная отправка черновика в Qwen из редактора, микрофон реального
arm64-телефона, количественная точность распознавания на заданном наборе.
Изменения Whisper закоммичены в Phase 1 коммит `7b1d27a` (2026-10-26, по явному
разрешению пользователя); remote не настроен, пуш не выполнялся.

Подробности по отмене — в `diagnostics/cancellation-20261025_nothrottle_run{1,2,3}/`.
Подробности M3 — в `docs/IMPLEMENTATION_REPORT.md` §11–18.

## Состояние на 2026-10-24
M1 закрыта, M2 почти закрыта: офлайн-чат на Qwen3-1.7B Q4_K_M работает на эмуляторе
через собранный llama.cpp. Последние проверки: `assembleDebug` — успешно,
`testDebugUnitTest` — 25 тестов без падений, `lintDebug` — 0 ошибок (21 warning).
Подробности и ограничения — в `docs/STATE.md`.

Инференс проверен вручную на эмуляторе: `Hello` → `Hello! How can I assist you today?`,
история пережила перезапуск. Дополнительно проверены многоходовый контекст, «Стоп» до и
во время генерации, генерация после отмены, «Новый чат», скачивание модели в приложении
(SHA-256 сошёлся), импорт GGUF через SAF, генерация без сети. Подробная таблица
PASS/NOT TESTED, методика замеров и инструкция для телефона:
`docs/IMPLEMENTATION_REPORT.md`. На реальном телефоне не проверялось; APK на телефон
не устанавливался.

## Как воспроизвести проверку на эмуляторе
1. Сборка: `.\gradlew.bat :app:assembleDebug`.
2. Установка: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
3. Подготовка модели (веса в репозиторий не входят, `models/` в `.gitignore`):
   - `adb push models\Qwen_Qwen3-1.7B-Q4_K_M.gguf.part /data/local/tmp/model.gguf`
   - `adb shell "run-as com.example.localassistant mkdir -p files/models"`
   - `adb shell "run-as com.example.localassistant cp /data/local/tmp/model.gguf files/models/model.gguf"`
   - `adb shell "rm -f /data/local/tmp/model.gguf"`
   - сверить SHA-256: `72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`.
4. Запуск: `adb shell am start -n com.example.localassistant/.MainActivity`.
5. Логи движка: `adb logcat | Select-String "LlmNative|LlamaEngine"`.

Emulator: `emulator-5554`, x86_64, API 37. Для 1.28-ГБ модели нужен запас RAM;
эмулятор запускался с `-memory 6144`.

## Доступные артефакты
- `third_party/llama.cpp` — вендоренная v0.7.0 (commit `620d2225`), лицензия MIT,
  происхождение и SHA-256 архива: `third_party/llama.cpp/VENDORING.md`.
- `models/Qwen_Qwen3-1.7B-Q4_K_M.gguf.part` — полная копия весов (1 282 439 584 байт)
  только на этой машине; можно переименовать в `.gguf` или пушить как есть.
- `Qwen_Implement.md` — исходное задание; в Git не добавлен.

## Проверенные команды
```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```
Перед использованием убедиться, что модуль `:app` и задачи существуют.
Git-коммит: `7b1d27a` на `master` (Phase 1, локально, remote не настроен).
`.gitignore` содержит `/models/`, `*.gguf.part`, `whisper/`, `.idea/`, `diagnostics/`.

## Хрупкие места
- `abort_callback` в `whisper_jni.cpp` вызывает JNI-метод `get` на `AtomicBoolean` при
  **каждом** вызове. Это безопасно: callback вызывается один раз на шаг декодирования
  (не на токен), а чтение boolean — дешёвая операция. Троттлинг (mod-8) **не использовать**:
  он добавлял до ~5 с задержки отмены (диапазон 9920–15721 мс) против ~5 с без него (4888–5328 мс).
- `llama_tokenize` этой ревизии сигнализирует нехватку буфера отрицательным числом;
  размерный проход с `nullptr, 0` всегда возвращает отрицательное значение — это норма,
  а не ошибка. Не «исправлять» обратно.
- `llama_model_params` не имеет `use_mmap`: используется `load_mode`.
- Kotlin-логи в release-сборке отсутствуют по правилам R8; для диагностики — debug.
- PowerShell 5.1: `Set-Content -Encoding UTF8` добавляет BOM и ломает TOML/JSON —
  писать через `[IO.File]::WriteAllText(..., UTF8Encoding($false))`.
- `adb exec-out screencap -p > file` в PowerShell 5.1 портит PNG; использовать
  `adb shell screencap -p /sdcard/s.png` + `adb pull`.

## Открытые задачи
1. Прогнать на реальном телефоне по `docs/IMPLEMENTATION_REPORT.md` §9 и заполнить таблицу
   §5 фактическими данными; APK на телефон не ставить без разрешения.
2. Согласовать исправление дефектов из §7 отчёта: автоскролл к новому ответу, потеря
   частичного текста при убийстве процесса, недоступность импорта/удаления при готовой модели.
3. ~~M3 (STT)~~: реализована и проверена на эмуляторе. Остаётся: микрофон реального
    arm64-телефона, количественная точность распознавания на заданном наборе, явная
    отправка черновика в Qwen из редактора. Изменения Whisper закоммичены в
    Phase 1 коммит `7b1d27a` (по явному разрешению); remote не настроен, пуш не выполнялся.
4. Проверить STT на реальном телефоне (arm64-v8a); текущие замеры — только
   x86_64-эмулятор.
5. M4: локальные заметки (добавление, чтение, изменение, удаление через UI).

## Инструментация (debug)
`LlmNative` пишет `model load took N ms`, `prompt ready`, `first token after N ms`, RSS в кБ,
`generation finished: N tokens … (X tok/s)`; `ChatViewModel` — `first visible token after N ms`
и `turn finished in N ms, chars=N, cancelled=…`. Тексты сообщений в логи не пишутся.

## Как воспроизвести проверку отмены STT (whisper)
1. Сборка: `.\gradlew.bat :app:assembleDebug` (собирает и `:whisper`).
2. Установка: `adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk`.
3. Подготовка модели (веса в репозиторий не входят):
   - `adb -s emulator-5554 push <путь к ggml-base.bin> /data/local/tmp/whtmp/ggml-base.bin`
   - `adb -s emulator-5554 shell "run-as com.example.localassistant sh -c 'mkdir -p files/whisper && cat /data/local/tmp/whtmp/ggml-base.bin > files/whisper/ggml-base.bin'"`
   - `adb -s emulator-5554 shell "rm -rf /data/local/tmp/whtmp"`
   - сверить MD5: `335f34f382e396519b6359d32c786317`.
   - **Важно (наблюдаемое, не подтверждённое объяснение):** при каждом запуске
     `connectedDebugAndroidTest` в этой сессии модель отсутствовала, и
     `run-as com.example.localassistant` возвращал `unknown package`. Модель приходилось
     восстанавливать каждый раз: `adb install -r` → `adb push` → `run-as … cat >`.
     Не утверждается, что Gradle **универсально** удаляет приложение. Путь:
     `files/whisper/ggml-base.bin` в пакете `com.example.localassistant`.
4. Запуск только целевого теста:
   `.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.localassistant.asr.WhisperCancellationTest'`
5. Результаты: `app\build\outputs\androidTest-results\connected\debug\TEST-*.xml` и
   `...\logcat-*.txt`. Ключевые строки: `CANCEL_RESULT stopAfterFlagMs=…`,
   `RECOVERY_RESULT`, `DIAG whisper_full returned code=…`, `DIAG abort observed by native callback invocation=…`.

## Что не делать без подтверждения
`git add`, `git commit`, `git push`, установка APK, удаление модели или истории диалога,
добавление разрешений в Manifest, новые зависимости, апгрейд Gradle/AGP/Kotlin/NDK/CMake.
