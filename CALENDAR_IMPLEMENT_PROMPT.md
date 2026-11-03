The Android app is stable on physical device CPH2581. The current local
assistant supports chat, Whisper speech input, Qwen generation, truncated
response continuation, and conversation persistence.

Implement the following work in two strictly ordered phases.

Do not create any Git commit automatically.
After implementation, build, installation, and your own verification, report
the results and wait for my explicit approval. Only after I explicitly
approve, create one local commit. Do not push; no remote is configured.

Do not delete existing conversations, downloaded models, or app data.
Do not change the existing "+" behavior.
Do not implement multi-conversation history.
Do not alter the existing adaptive response-token budget.
Do not reintroduce silent truncation.
Prevent special tokens such as <|im_end|> and <|im_start|> from appearing in
visible assistant text.

============================================================
PHASE 1 — GOOGLE CALENDAR CONNECTION AND MODEL TOOLS
============================================================

First implement Google Calendar connection, Settings integration, and the
five calendar operations available to the model.

### 1. Settings: Google Calendar section

Add a new section in Settings named:

Google Календарь

It must show:
- connection status: "Не подключён" or "Подключён";
- the connected Google account email, if available;
- a button "Подключить Google Календарь" when not connected;
- a button "Отключить" when connected;
- clear status/error messages for authorization failures.

Use Android Google Identity Services / appropriate Google authorization flow
for user consent. Request only the minimum Calendar scopes required for the
implemented operations.

Store OAuth tokens and account state only in app-private secure storage.
Never log tokens. Never commit client IDs, secrets, tokens, or credentials.

If Google Cloud Console configuration is required (OAuth client ID, package
name, SHA-1 fingerprint), clearly report the exact missing configuration and
the exact values the user must add. Do not invent credentials.

### 2. Calendar operations for the model

Implement exactly these five operations and expose them to the model as safe,
structured tool/function calls:

1. READ_CALENDAR
   - Read events for today, tomorrow, a specific date, or a date range.
   - Return event title, start/end time, calendar name if available, and
     event ID.
   - Support a reasonable default range when the user says "today" or
     "tomorrow".

2. CREATE_CALENDAR_EVENT
   - Create an event with title, start date/time, end date/time or duration,
     optional description, and optional location.
   - Require a clear event title and valid time information.
   - If essential information is missing, ask the user for it instead of
     inventing values.

3. UPDATE_CALENDAR_EVENT
   - Update an existing event.
   - The model must first identify the target event, preferably by event ID
     returned from a search/read operation.
   - Support changing title, time, description, and location.
   - Do not update an event based on an ambiguous match.

4. DELETE_CALENDAR_EVENT
   - Delete an existing event.
   - The model must first identify the target event and confirm the exact
     event title and time with the user.
   - Never delete based on an ambiguous match.
   - Never delete multiple events in one silent operation.

5. SEARCH_CALENDAR_EVENTS
   - Search events by title, keyword, date, or date range.
   - Return matching events with enough information for the user to select
     the intended one.

### 3. Safety rules — mandatory

Apply these rules to every calendar operation:

- If Google Calendar is not connected, do not attempt any calendar operation.
  Instead, return a clear user-facing message:
  "Google Календарь не подключён. Подключите его в Настройках → Google
  Календарь."
  Include a convenient way to open the Google Calendar Settings section.

- Before UPDATE or DELETE, identify the exact target event.
- If more than one event matches, ask the user to choose between the
  candidates.
- Before DELETE, explicitly confirm the event title and scheduled time with
  the user.
- Never silently delete events.
- Never silently update events when the requested change is ambiguous.
- Never invent event IDs, dates, times, calendars, or attendees.
- Use the device-local timezone for interpreting relative dates such as
  "today", "tomorrow", and "this evening".
- Handle authorization expiry, revoked access, network errors, and Calendar
  API errors gracefully.
- Never expose raw OAuth tokens, refresh tokens, client secrets, or full raw
  API error payloads in the chat UI.
- All calendar network/API operations must run off the main thread.
- Calendar failures must produce a clear user-visible error, not an app
  crash.

### 4. Model integration

Integrate calendar operations with the existing Qwen chat flow in a safe,
structured way:

- The model should be able to request a calendar operation through a
  structured tool call.
- The app must validate the requested operation and parameters before
  execution.
- The app—not the model—must perform the actual Calendar API call.
- The app must return a concise structured result to the model so it can
  formulate a user-friendly answer.
- Do not allow free-form shell commands, arbitrary HTTP requests, or direct
  access to OAuth tokens by the model.
- If the model requests an unsupported or unsafe operation, reject it and
  explain the available calendar capabilities.

### 5. Phase 1 verification

Build the debug APK.
Ask me to connect CPH2581 if necessary.
Install without deleting app data:

adb -s 2d46124b install -r app\build\outputs\apk\debug\app-debug.apk

Verify:
- the new Settings section appears;
- connection flow can be started;
- an unconnected calendar request produces the required message and a way to
  open Settings;
- after connection, READ and SEARCH work;
- CREATE works only with sufficient information;
- UPDATE and DELETE require unambiguous event identification and user
  confirmation;
- cancellation, authorization errors, and network errors do not crash the
  app;
- existing chat, Whisper input, model management, and conversation
  persistence still work.

Add unit tests where practical for:
- calendar tool-request validation;
- missing-connection handling;
- ambiguous event matching;
- required confirmation before DELETE;
- safe handling of authorization/network errors.

Do not create a commit yet.

============================================================
PHASE 2 — UNIFY ALL SETTINGS BUTTON STYLES
============================================================

Only after Phase 1 is implemented and verified, update the Settings UI so
that all action buttons have one consistent style.

### Current problems

In Settings → Модели:
- "Заменить GGUF" and "Удалить" are in one horizontal row.
- The "Удалить" text wraps vertically because the button is too narrow.
- All buttons have only a faint gray outline and nearly transparent fill,
  so they blend into the section background.

The same layout and styling problem affects the two buttons in
Настройки → Распознавание речи.

The new Google Calendar buttons must use the same final button style.

### Required layout

For each pair of action buttons:
- "Заменить GGUF" and "Удалить";
- the two speech-recognition buttons;
- "Подключить Google Календарь" and "Отключить";

arrange the buttons vertically, one below another, with consistent spacing.

Each button must:
- occupy the full available card width or a consistent width;
- keep its label on one line;
- never wrap text vertically;
- have sufficient height and horizontal/vertical padding;
- remain readable and accessible.

### Required visual style

Make all Settings action buttons visually consistent:

- fill color slightly darker than the section/card background;
- a subtle but clearly visible border;
- consistent corner radius;
- consistent text color, typography, and padding;
- consistent disabled state;
- consistent pressed state;
- consistent destructive styling only where appropriate, while keeping the
  same size and layout rules.

Do not make destructive buttons visually identical to safe actions if the
project already has a destructive-color convention; but all buttons must
share the same dimensions, placement, padding, and overall structure.

The final result must look coherent:
- Модели buttons;
- Распознавание речи buttons;
- Google Календарь buttons.

============================================================
PHASE 2 VERIFICATION
============================================================

Build the debug APK again.
Ask me to connect CPH2581 if necessary.
Install without deleting app data:

adb -s 2d46124b install -r app\build\outputs\apk\debug\app-debug.apk

Verify on the physical device:
- no button label wraps vertically;
- all Settings action buttons have a consistent, clearly visible style;
- Модели, Распознавание речи, and Google Календарь sections look coherent;
- replacing/removing models and speech-recognition actions still work;
- Google Calendar connection/disconnection still works;
- existing chat, Whisper input, generation, continuation, and persistence
  still work.

Add or update UI tests only where practical; do not overengineer.

============================================================
GIT RULE — COMMIT ONLY AFTER MY APPROVAL
============================================================

Do not create any Git commit automatically.

After both phases are implemented, built, installed, and verified:
1. Report all changed files and a concise summary.
2. Provide build, lint, and test results.
3. Ask me to perform my own manual verification on CPH2581.
4. Wait for my explicit approval.

Only after I explicitly approve, create one local commit with this message:

feat: add Google Calendar tools and unify Settings actions

Do not push; no remote is configured.

If I report a problem, do not commit; diagnose and fix it first, then ask
for approval again.

============================================================
FINAL REPORT BEFORE MY APPROVAL
============================================================

Provide a concise Russian report with:

- Phase 1:
  - Calendar connection approach and required Google Cloud configuration;
  - requested OAuth scopes and why they are necessary;
  - implemented five operations;
  - safety rules and confirmation flow;
  - how tool calls are validated and executed;
  - behavior when Calendar is not connected;
  - files and functions changed;
  - tests added or updated.

- Phase 2:
  - button layout changes;
  - unified visual style;
  - files and composables changed.

- Verification:
  - unit-test results;
  - build/lint results;
  - installation result;
  - manual verification results on CPH2581;
  - installed APK path and SHA-256;
  - explicit request for my manual verification and commit approval.