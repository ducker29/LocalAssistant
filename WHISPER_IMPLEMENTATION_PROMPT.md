# Autonomous milestone: checkpoint Qwen chat and integrate offline Whisper

Execute this task in the existing Android project. Do not stop at a plan.
Agent prompts and code identifiers are English; user-facing strings, reports,
and the final response must be Russian.

## 1. Read context and preserve existing work

Read AGENTS.md, project skills, STATE.md, HANDOFF.md, PLAN.md, DECISIONS.md,
and IMPLEMENTATION_REPORT.md if present. Inspect actual files, git status,
SDK setup and connected devices. Preserve unrelated modifications.

The current Qwen3-1.7B Q4_K_M chat reportedly works on an x86_64 emulator.
Physical-phone testing is intentionally deferred. Do not claim it was done.
This task authorizes moving on to Whisper without waiting for phone tests.

## 2. Checkpoint and upload current Qwen work FIRST

The user requests that current changes be committed and uploaded before
Whisper integration. This authorizes a normal local commit and normal push
to the existing, unambiguous project remote and current non-protected branch.

Before committing:
- Review git diff and status; identify the current Qwen milestone changes.
- Check ignore rules and staged content for GGUF/BIN weights, model partials,
  APKs, build outputs, secrets, local.properties and machine-specific files.
- Stage explicit relevant paths, not a blind git add of the entire workspace.
- Keep required vendored sources, licenses and reproducibility metadata.
- Do not include unrelated user changes or confidential audio recordings.
- Run the existing reasonable baseline build/test checks; record results.
- Use a descriptive message such as feat: implement offline Qwen chat.

Inspect remote URL, branch, tracking branch and outgoing commits before push.
Never expose credentials in logs. Do not create a remote/repository, change
visibility, invent a URL, push unrelated history, force-push or rewrite history.
If there is no suitable remote, auth is unavailable, the remote is ambiguous,
or only a protected/default production branch is available, save the local
checkpoint and clearly record why upload is blocked. Continue independent
Whisper work; do not wait indefinitely for a response.
Record checkpoint hash, branch and actual push result in the report.
Do not perform a second push of Whisper changes: leave those changes for review.

## 3. Scope and authorization

Implement record -> stop -> local transcription -> editable chat draft ->
explicit Send -> existing Qwen response. Never send transcripts automatically.

Authorized: project edits, public dependency/model downloads, builds/tests,
necessary SDK tools with already accepted licenses, and installation/launch
of this app's debug APK on the existing clearly identified emulator.
Use explicit adb serials. Never install on a physical phone in this task.

No cloud speech APIs, Android system speech-recognition service, external
inference server, API keys, analytics, RAG, calendar, background recording,
wake words, continuous listening, streaming ASR or GPU optimization.
Do not alter global OpenCode permissions, SDK/AVD paths, system settings,
other apps or user files. Do not bypass permission gates or accept new licenses.
Do not upgrade unrelated Gradle/Kotlin/AGP dependencies.

## 4. Implementation plan and dependency choice

Create a short checklist and execute it autonomously in small verified phases:
checkpoint -> native ASR -> model management -> recording -> chat integration
-> tests and emulator verification -> documentation.

Use ggml-org/whisper.cpp. Inspect its actual current Android example,
headers, CMake and documentation before coding. Pin a FULL commit SHA.
Record upstream URL, licenses, revision and reproducible acquisition/vendoring.
Do not invent native API signatures or use untracked local-only dependencies.

Use multilingual Whisper base, file ggml-base.bin, from the model repository
linked by upstream: ggerganov/whisper.cpp on Hugging Face.
Do NOT use base.en: Russian recognition is required.
Verify the actual artifact, revision, length and published digest before use.
Do not fabricate hashes or download the entire repository. Do not silently
switch to tiny/small or another model. If base is unavailable, finish independent
work and report the model blocker.

Important: Whisper's selected .bin format is not Qwen GGUF. Do not reuse a
GGUF header validator for Whisper. Validate according to the selected engine,
artifact integrity metadata and real native loading.

## 5. Native integration: preserve Qwen and avoid ggml conflicts

Build both arm64-v8a and the current emulator ABI, normally x86_64.
Start with conservative CPU inference; no host-specific CPU instructions.

llama.cpp and whisper.cpp may vendor different ggml revisions and export
similarly named targets/symbols. Inspect the existing native build first.
Design explicit target/symbol isolation, e.g. separate native libraries with
hidden internal visibility and explicit JNI exports where appropriate.
Do not assume separate .so filenames alone guarantee symbol isolation.
Do not blindly replace llama.cpp's ggml with Whisper's or mix incompatible
headers/libraries. Avoid duplicate global CMake targets and ABI mismatches.
Document the chosen isolation strategy and verify Qwen -> Whisper -> Qwen
in the same app process, not only two isolated smoke tests.

Define a small Kotlin ASR abstraction with load, transcribe, cancel and close.
Run native loading/inference and audio conversion off the UI thread.
Serialize native operations; stop/join work before freeing resources.
Implement cancellation using the pinned library's supported mechanism.
Handle Kotlin coroutine cancellation and native cancellation coherently.
Release contexts, JNI references, audio handles and files deterministically.
Do not use the same native context concurrently.

## 6. Model management and disk space

Reuse/refactor the existing model download/import infrastructure only where
appropriate. Keep separate model identities, paths and states for Qwen/Whisper.
Never overwrite or invalidate the existing Qwen weights.

Provide Russian UI to download Whisper base or import its compatible .bin
through the Android document picker. Store it in app-private persistent storage,
not APK assets or an evictable cache. Do not request broad storage permissions.

Support progress, cancellation, retry and clear errors. Stream transfers,
use .part files, validate completion and published integrity metadata, then
atomically promote when possible. Never load partial weights. Resume only
with verified HTTP range semantics; otherwise restart safely.

Keep host models ignored in the existing project location on E: if appropriate.
Check space on host drives and the AVD host volume as well as guest /data.
Avoid duplicate model downloads and unnecessary permanent transfer copies.
Do not delete unrelated files/caches. Never commit weights or personal audio.

Network is used only for explicit model acquisition; recording, transcription
and Qwen inference must remain fully local after model setup.

## 7. Audio recording and privacy

Implement foreground-only, user-initiated short audio recording.
Add RECORD_AUDIO to the manifest and request runtime permission only when
the user starts recording. Handle denial/permanent denial gracefully.
Do not ask for microphone permission on every application launch.
Show an obvious recording indicator, elapsed time, Stop and Cancel actions.

Use AudioRecord or an equally justified local implementation. Produce the
actual input format expected by the pinned Whisper API, typically normalized
mono 16 kHz PCM float samples. Validate capture format and sample counts.
If capture occurs at another supported rate, properly resample: never just
relabel a 48 kHz recording as 16 kHz. Do not interpret AAC bytes as PCM.
Handle initialization failure, read errors, silence, empty/truncated input,
microphone unavailability and bounded buffering without busy loops.

MVP recording limit: 60 seconds, with a visible limit and safe automatic stop.
No ongoing recording after leaving the app or locking the screen: stop safely
and keep a recoverable stopped draft if practical; never silently resume.
On process death, do not pretend recording/transcription is still active.

Keep temporary recordings private, never upload them, do not log transcript
contents by default, and delete task-owned temporary audio after completion,
cancellation or abandoned-work cleanup. Explain draft/history persistence.
Do not retain raw recordings indefinitely. No foreground service is needed
for this foreground-only MVP unless technically justified and documented.

## 8. Recognition behavior and UI integration

Default ASR language: Russian (ru), transcribe rather than translate.
Optional language auto-detection may be offered, but is not required.
Avoid feeding unrelated chat history into recognition prompts.
Handle silent input and suspicious/no-speech output conservatively; do not
claim a speech detector exists unless it is actually implemented.
Do not add an extra VAD model for this milestone.

Workflow:
1. User taps microphone.
2. Explain/download/import missing Whisper model as needed.
3. Request audio permission and start recording after explicit user action.
4. User stops recording or the duration limit stops it.
5. Run local transcription with progress/state feedback and Cancel.
6. Place recognized text into the editable chat input.
7. User reviews/edits and explicitly presses Send.
8. Existing Qwen chat generates a response.

If a text draft already exists, preserve it: append with a clear separator
or provide a simple explicit choice; never silently overwrite user text.
Never persist a draft as a sent user message before explicit Send.
Empty/failed/cancelled recognition must not create messages or trigger Qwen.

State transitions must be explicit: missing/downloading/loading/ready,
recording, transcribing, cancelled/error. Disable conflicting operations.
Reuse Material 3 styling and accessibility labels. Handle activity recreation
without duplicate recorders, duplicate native contexts or lost ownership.

## 9. Memory and scheduling

Do not assume both models fit concurrently just because their weight files fit.
Avoid simultaneous Qwen generation and Whisper transcription.
Implement an explicit safe handoff: finish/cancel and join work before unloading
or loading engines. Preserve conversation text independently of native contexts.
If memory is tight, unload inactive model contexts, then reload when needed.
Do not unload a model every token or reload for every UI recomposition.

Measure process memory where practical. Distinguish free guest RAM from
MemAvailable and total allocation. Keep any previously approved emulator RAM
settings; do not repeatedly change AVD configuration. Report resource blockers
instead of hiding OOM or replacing local inference with fake results.

## 10. Testing and runtime verification

Run existing Qwen regression tests plus new tests for:
- Audio/transcription state machine and permission-result handling.
- Cancellation, failure, empty input and recording duration limit.
- PCM conversion/resampling logic and input bounds where implemented.
- Draft preservation and no automatic sending.
- Model identity/storage validation and interrupted downloads.
- Scheduler/native-resource ownership where unit testing is feasible.

Run assembleDebug, unit tests and lint. Verify both required native ABIs are
built and packaged. Report exact commands and results; do not invent outcomes.

On the authorized emulator verify:
- Existing Qwen still loads and answers.
- Real whisper.cpp loads the real multilingual base weights.
- Transcribe a known nonempty speech fixture using actual native inference.
- Verify Russian speech using a public redistributable fixture if available;
  record source/license and expected words. Do not upload private speech or
  require paid/external TTS. An English fixture alone does not verify Russian.
- Exercise draft insertion/editing and explicit Send to Qwen.
- Cancellation followed by another transcription.
- Qwen -> Whisper -> Qwen in the same process.
- Permission denial, no audio and recoverable errors where feasible.
- Offline recognition and Qwen response after model provisioning.

Emulator microphone input may be unavailable or silence-only. In that case
use a clearly identified test fixture for ASR, but mark real microphone capture
and its full end-to-end UI path NOT TESTED. A fixture-fed debug test must not
be presented as verified microphone recording. Do not add production backdoors.

Temporarily change only emulator networking for offline testing if needed;
record and restore its original state, including after failures.
Do not change host network settings.

Keep physical-phone performance and microphone verification explicitly pending.
Do not claim emulator results establish real phone speed or memory suitability.

## 11. Documentation and finish criteria

Update existing STATE/HANDOFF/PLAN/DECISIONS and the implementation report.
Include full upstream SHAs, model provenance/integrity/license, ABI support,
CMake/NDK requirements, native isolation design, audio format, recording limits,
model-memory handoff, privacy behavior and current blockers.

Provide Windows build/install instructions, APK location, model import steps,
a short phone-test checklist for later, and measured load/transcription timings
with audio duration and device identified. Mark unmeasured metrics as such.

Final Russian report must distinguish PASS / FAIL / NOT TESTED for:
- Qwen checkpoint commit and remote push.
- arm64-v8a and x86_64 builds and APK contents.
- Existing Qwen regression.
- Real Whisper inference and Russian fixture recognition.
- Actual microphone recording.
- Transcript draft editing and explicit send to Qwen.
- Cancellation/recovery and sequential model handoff.
- User download/import and offline operation.
- Physical phone verification (deferred).

Leave Whisper changes uncommitted and unpushed for review.
Do not mark the milestone fully verified if microphone or native inference
has not been tested. Do not substitute a fake transcription engine or cloud API.

Continue autonomously within these boundaries. If blocked, attempt bounded,
evidence-based fixes, complete independent work and record the exact minimal
user action needed. No routine approval questions between phases.
