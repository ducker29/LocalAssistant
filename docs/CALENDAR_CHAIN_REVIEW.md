# Отчёт: трассировка цепочки «запрос о календаре → доступ к Google Calendar»

Дата: 2026-10-27
Статус: только чтение, код не изменялся, билды не запускались.

---

## 1. Схема архитектуры (текущая)

```
пользователь ──текст──▶ ChatViewModel.send()
                          │
                          ▼
                   runPlanner()          ◀── локальный LLM (Qwen 1.7B), 256 токенов, temp=0
                          │  system = PLANNER_SYSTEM_PROMPT (статика, без состояния)
                          │  + последние 3 хода + userText
                          ▼
                   raw string ──▶ CalendarToolParser.parse()
                          │
              ┌───────────┴────────────┐
              ▼                        ▼
     ToolParseError → None    Ok → ToolCall(req)
              │                        │
              ▼                        ▼
     обычный чат-ответ     CalendarViewModel.run(req)
     (модель НЕ знает         │  ensureToken()
      про календарь)          │
                              ▼
                   CalendarToolExecutor.executeSuspend()
                              │  is_connected() = (cachedToken != null)
                              ▼
                   AuthorizedCalendarGateway
                              │  ensureToken() ещё раз
                              ▼
                   HttpCalendarGateway.listEvents()
                              │  GET https://www.googleapis.com/calendar/v3/users/me/events?…
                              │  Authorization: Bearer <token>
                              ▼
                   Google Calendar API v3
```

**Ключевое:** результат тулза **не возвращается модели**. `CalendarViewModel.run` → `executeSuspend` → `CalendarToolResult` становится **финальным assistant-сообщением** (`ChatViewModel.kt:134:177` — все ветви заканчиваются `return@launch`). Второго прохода модели нет.

---

## 2. Таблица по стадиям

| # | Стадия | Файл:функция:строки | Текущее поведение | Вероятная точка отказа | Уверенность |
|---|---|---|---|---|---|
| 1 | Распознавание намерения | `ChatViewModel.kt:send():133` | `runPlanner` вызывается **для каждого** сообщения | — | Высокая |
| 2 | Промпт планировщика | `CalendarToolSpec.kt:PLANNER_SYSTEM_PROMPT:22-27` | Статический текст, 5 тулзов: READ/CREATE/UPDATE/DELETE/SEARCH | Состояние подключения **не передаётся** модели — она не знает, что Connected/Disconnected | Высокая |
| 3 | Генерация | `ChatViewModel.kt:runPlannerGeneration():527-548` | 1 вызов, 256 токенов, temp=0, нет повторных попыток | Модель 1.7B может не напечатать валидный JSON | Высокая |
| 4 | Парсинг | `CalendarToolRequest.kt:parse():57-102` | Строгий `JSONObject(raw)`; `NONE` **не** в `CalendarOp` → «Неизвестная операция: NONE» | Любая лишняя буква вокруг JSON → тихий `None` → обычный чат | Высокая |
| 5 | Тихий fallback | `ChatViewModel.kt:519-522` | `ToolParseError → PlannerOutcome.None` без повторного запроса модели | Пользователь видит ответ модели, не знающей о календаре | Высокая |
| 6 | Состояние подключения | `CalendarAuthManager.kt:ensureToken():311-372` | Кэш токена → `Ok`; иначе один `authorize()`; silent refresh **нет** | Истёкший токен → `RequiresReconnect` | Высокая |
| 7 | Исполнитель | `CalendarToolExecutor.kt:executeSuspend():96-154` | `is_connected() = cachedToken != null`; READ/SEARCH → `gateway.listEvents`/`searchEvents` | Если `cachedToken == null` → `NotConnected` | Высокая |
| 8 | Gateway (HTTP) | `HttpCalendarGateway.kt:listEvents():23-30`, `request():66-114` | `GET …/users/me/events?timeMin&timeMax&maxResults`; `Authorization: Bearer`; malformed JSON → `CalendarApiError.Network` | Классификация ошибок грубая | Средняя |
| 9 | Возврат модели | отсутствует | Результат тулза **не** возвращается модели, становится финальным ответом | Модель не может «попробовать иначе» | Высокая |

---

## 3. Наиболее вероятные причины «не могу посмотреть календарь» (по убыванию)

**1. Модель-планировщик не выпустила валидный JSON (НАИБОЛЕЕ ВЕРОЯТНО).**
Qwen 1.7B на 256 токенах, temp=0, один проход. Промпт требует «ровно один JSON-объект, без текста», но малая модель часто добавляет markdown-обёртку, пояснение или опечатку. Парсер (`CalendarToolRequest.kt:57-62`) делает `JSONObject(raw)` — **без** извлечения JSON из текста. Результат: `ToolParseError` → тихий `None` → обычный чат-ответ модели, которая **не знает о календаре** (`SYSTEM_PROMPT` в `ChatViewModel.kt:598-600` — 2 строки без упоминания тулзов). Пользователь и видит «не могу посмотреть».
*Диагностика:* logcat, фильтр `planner raw output` + `parser REJECTED`. Строка `parser REJECTED: <reason>` — подтверждение.

**2. Модель выпустила `{"tool":"NONE"}`.**
`NONE` не входит в `enum CalendarOp` (`CalendarToolRequest.kt:9-16`), парсер возвращает `ToolParseError("Неизвестная операция: NONE")` → `None` → обычный чат. Смыслово правильно (модель решила, что календарь не нужен), но лог вводит в заблуждение.
*Диагностика:* та же строка `parser REJECTED: Неизвестная операция: NONE`.

**3. Токен истёк / не кэширован → `ensureToken` → `RequiresReconnect`.**
`CalendarAuthManager.kt:324` — если `cachedToken != null`, токен берётся из кэша **без проверки expiry**. Истечение обнаруживается только по HTTP 401 → `invalidateRejectedToken` (`:378-400`) → `NeedsReconnect("Сессия календаря истекла…")`. Если же пользователь только что подключился, но процесс перезапустился — `cachedToken` инициализируется из `tokenStore.accessToken()` (`:97-103`), обычно токен валиден.
*Диагностика:* logcat, фильтр `ensureToken` + `invalidateRejectedToken`.

**4. `is_connected()` в исполнителе — `false`.**
`LocalAssistantApp.kt:48-51` передаёт `is_connected = { calendarAuthManager.currentToken() != null }`. Если токен в кэше есть, но `CalendarAuthManager` пересоздан (не должно случаться — он singleton), проверка может не сработать. Маловероятно, но возможно при странном порядке инициализации.
*Диагностика:* logcat, фильтр `executor:.*connected=false`.

**5. HTTP-ошибка 403 (scope/домен API).**
Scope `https://www.googleapis.com/auth/calendar.events` (`CalendarScopes.kt:12`) — достаточен для `listEvents`/`searchEvents`. Но если проект в Google Cloud Console не подключён к Calendar API, будет 403 `accessNotConfigured`.
*Диагностика:* logcat, фильтр `CalendarApiException` + код ответа.

---

## 4. Прямые ответы

| Вопрос | Ответ |
|---|---|
| **Тулз чтения реально доступен модели?** | Да. `READ` и `SEARCH` описаны в `PLANNER_SYSTEM_PROMPT` (`CalendarToolSpec.kt:9-19`). Но это **не** формальный tool-calling API — это текстовая инструкция «напечатай JSON». Модель может его не напечатать. |
| **Состояние подключения передаётся планировщику?** | **Нет.** `PLANNER_SYSTEM_PROMPT` статичен. `connectionState` читается только в логе (`ChatViewModel.kt:505`). Модель не знает, Connected или Disconnected. |
| **Scope достаточен для чтения событий?** | Да. `calendar.events` (`CalendarScopes.kt:12`) покрывает `events.list` и `events.query`. `calendarlist.readonly` не запрашивается — но для `users/me/events` он не нужен. |
| **Токен реально прикладывается к запросам?** | Да. `HttpCalendarGateway.kt:85` — `setRequestProperty("Authorization", "Bearer $token")`. Токен берётся из `tokenProvider()` → `calendarAuthManager.currentToken()`. |
| **Gateway вызывает реальный endpoint чтения?** | Да. `GET https://www.googleapis.com/calendar/v3/users/me/events?timeMin=…&timeMax=…&maxResults=N` (`HttpCalendarGateway.kt:169`, `:23-30`). |
| **Ошибки отличимы от «не подключено»?** | Частично. `NotConnected` — нет токена. `Error("Сессия истекла")` — 401. `Error("Нет доступа к API")` — 403. `Error("Ошибка сети")` — IOException/малформный JSON. Но пользователь видит это как один текст в чате, без возможности для модели «попробовать иначе». |

---

## 5. Минимальные диагностические шаги (без изменения кода)

1. **logcat, фильтр `planner` + `parser`:**
   ```
   adb -s 2d46124b logcat | Select-String "planner|parser|executor"
   ```
   Послать «Что у меня завтра в календаре?» и посмотреть:
   - `planner raw output (len=…): '…'` — что реально напечатала модель
   - `parser OK` / `parser REJECTED: <reason>` — парся ли

2. **Если `parser REJECTED`** — проблема на стороне модели (пункт 3.1 выше). Решение: извлекать JSON из raw-текста (regex `\{.*\}` с `DOTALL`) перед `JSONObject()`.

3. **Если `parser OK`** — смотреть `executor:` и `HttpCalendarGateway`:
   ```
   adb -s 2d46124b logcat | Select-String "executor|CalendarApi|ensureToken"
   ```
   - `executor:.*connected=false` → токен не в кэше
   - `ensureToken.*RequiresReconnect` → токен истёк
   - `CalendarApiException.*401|403` → HTTP-ошибка

4. **Проверить, что модель видит результат:** отправить «Что в календаре на этой неделе?», получить ответ, затем «А завтра?». Если второй ответ не учитывает результат первого — подтверждено, что tool result не возвращается модели (архитектурное ограничение).

---

## Вывод

Наиболее вероятная причина — **модель 1.7B не стабильно печатает валидный JSON** (пункт 3.1), а парсер слишком строгий (`JSONObject(raw)` без извлечения из текста). Это требует runtime-подтверждения через logcat, но кодовая трассировка указывает именно на это как на первое место проверки.
