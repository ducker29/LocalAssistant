Implement a working offline Android chat application powered by
Qwen3-1.7B GGUF, starting from the existing project.

This is an execution task, not a planning-only request.
Work autonomously through implementation, builds, tests, and available
runtime verification. Do not stop after presenting a plan or completing
only the UI.

Respond to the user in Russian.
Write user-facing application strings and project reports in Russian.
Use English for code identifiers.

==================================================
1. EXISTING PROJECT AND SCOPE
==================================================

The existing Kotlin + Jetpack Compose application already builds and
launches in an Android emulator, displaying "Hello Android".

The user recently moved the AVD data to another drive because C: was
running out of disk space.

Read AGENTS.md, existing project documentation, and available project
skills before making changes. Inspect actual files and configuration;
do not rely on assumptions from previous conversations.

The user now approves the entire milestone:
"Working local Qwen3-1.7B chat on Android".

If project documents still identify the previous milestone as current,
update their milestone/state entries consistently. Do not weaken safety
rules or silently rewrite unrelated instructions.

Do not implement Whisper, microphone recording, RAG, calendars, tools,
cloud inference, accounts, analytics, or remote services in this task.

Preserve the existing application ID and project identity.
Do not replace the whole project with a sample application.

==================================================
2. AUTONOMY AND SAFETY BOUNDARIES
==================================================

Within this task, the user authorizes:
- Editing project source code, build configuration, and documentation.
- Downloading public open-source dependencies and the selected model.
- Running project builds, tests, lint, and read-only diagnostics.
- Using sdkmanager to install necessary NDK/CMake components into the
  existing Android SDK, if licenses are already accepted.
- Installing/updating and launching this project’s debug APK on the
  currently connected Android emulator.
- Copying the selected model into that emulator for verification.

Do not ask for approval between routine implementation phases.

This task does NOT authorize:
- Changes to global OpenCode permissions.
- Disabling security checks or bypassing tool permission prompts.
- Changes to system environment variables or AVD storage configuration.
- Deleting user files, wiping emulator data, or uninstalling other apps.
- git reset --hard, git clean, force operations, commits, or pushes.
- Installing anything on physical phones.
- Paid services, API keys, cloud model inference, or credential access.
- Accepting new third-party license agreements on the user's behalf.

If multiple devices are connected, use the clearly identifiable emulator
from the current setup and always pass its explicit serial to adb.
If the intended emulator cannot be identified safely, continue builds
and host-side work, but report device testing as blocked.

If an action is blocked by tool permissions, do not bypass the block.
Continue other useful work and document the exact remaining blocker.

Before changing code, inspect git status.
Preserve unrelated user modifications.
Do not overwrite existing work.

==================================================
3. FIRST ACTIONS AND EXECUTION PLAN
==================================================

Inspect:
- Project modules and source structure.
- Kotlin, AGP, Gradle, Compose, and Java versions.
- minSdk / targetSdk / compileSdk.
- Version catalog and current dependency conventions.
- Installed SDK, NDK, CMake, and available adb devices.
- Emulator ABI and available storage/RAM.
- Free space on drives needed for dependencies, build outputs,
  host model download, and device model storage.

Create a short implementation checklist in the project documentation.
Then immediately execute it without waiting for approval.

Use small verifiable phases, but continue automatically:
A. Baseline and native integration.
B. Model acquisition/storage.
C. Chat UI and state.
D. Real inference.
E. Tests and runtime verification.
F. Final documentation and report.

Keep documentation updated so another agent can resume after interruption.

==================================================
4. LOCAL INFERENCE ENGINE
==================================================

Use llama.cpp as the local inference engine.

First inspect its current official Android documentation and example:
- ggml-org/llama.cpp
- docs/android.md
- examples/llama.android

Prefer adapting the official Android binding where suitable.
If it cannot be integrated reasonably, implement a minimal JNI wrapper
over llama.cpp with Kotlin-facing interfaces.

Do not invent native APIs from memory.
Inspect the selected revision's actual headers and examples.

Pin the exact llama.cpp commit/revision.
Record its revision, source location, and license in documentation.
Make dependency acquisition reproducible; do not depend on a local,
untracked source checkout that only exists on this workstation.

Use Gradle/CMake/NDK integration appropriate to the existing project.
Avoid unrelated Kotlin/AGP/Gradle upgrades.

Support:
- arm64-v8a for physical Android devices.
- The actual emulator ABI for current testing, commonly x86_64.

Do not assume a successful arm64 build can run on an x86_64 emulator.
Do not enable host-specific CPU instruction sets for Android binaries.
Use a conservative CPU backend first; GPU acceleration is out of scope.

Provide a small engine abstraction, for example:
- loadModel(...)
- generate(messages, parameters): Flow<...>
- cancelGeneration()
- resetConversation()
- unloadModel()/close()

Adapt exact types and function names to the chosen implementation.

Requirements:
- No inference, model loading, or large file I/O on the main thread.
- Serialize access to the native model/context.
- At most one active generation.
- Cancellation must safely stop native generation.
- Never free native resources while generation is still using them.
- Handle model load failures and native error conditions clearly.
- Release resources deliberately; do not rely solely on finalizers.
- Handle UTF-8 token fragments correctly.
- Do not reload the model for every message.

Use a single inference-owning component to avoid accidental duplicate
model loads during recomposition or activity recreation.

==================================================
5. MODEL SELECTION AND DOWNLOAD
==================================================

Required model: Qwen3-1.7B in GGUF format.
Preferred quantization: Q4_K_M.

First inspect the official repository:
Qwen/Qwen3-1.7B-GGUF

If an appropriate Q4_K_M artifact is available there, prefer it.
Otherwise use the reputable public repository:
bartowski/Qwen_Qwen3-1.7B-GGUF

Known candidate:
Qwen3-1.7B-Q4_K_M.gguf

Verify the actual artifact and download URL before using it.
Do not fabricate filenames, checksums, or file sizes.
Do not download the complete model repository or all quantizations.

Record:
- Model repository.
- Exact file name.
- Repository revision when obtainable.
- Actual download URL.
- Expected byte length when available.
- SHA-256/LFS hash when available.
- Model license and source attribution.

Do not silently substitute another model or quantization.
If Q4_K_M is unavailable, continue other implementation work and
document the blocker rather than downloading an unrelated model.

The model must NOT be committed to Git.
Do NOT bundle the GGUF in APK assets.

Implement an in-app model setup screen with:
1. Download the selected model.
2. Import a compatible GGUF through the Android system file picker.

Model storage:
- Store the active model in app-private persistent storage.
- Do not keep it only in an evictable cache directory.
- Do not require broad external-storage permissions.
- Copy imported content using streams, without loading the file into RAM.
- Validate the GGUF header and let the engine validate compatibility.

Download behavior:
- Show progress, bytes downloaded, and total size when known.
- Handle errors, retry, cancellation, and insufficient storage.
- Download to a temporary .part file.
- Promote to the final filename only after successful validation.
- Validate expected size and published checksum when available.
- Never load an incomplete download.
- Prevent duplicate downloads and duplicate model copies.
- Reuse an existing verified model.
- Clean up resources correctly after failure/cancellation.
- If resuming downloads, only append after validating the server's
  partial-content response; otherwise restart safely.

It is acceptable for background download to require the app process
to remain alive, provided this limitation is clearly documented and
partial files never appear as ready models.

For autonomous testing:
- Download at most one host copy of the selected model if needed.
- Prefer a project-local ignored model directory on a drive with space.
- Avoid large unnecessary files on C:.
- Reuse an existing verified model before downloading again.
- Transfer it into app-private emulator storage using an appropriate
  debug-only adb/run-as workflow, or exercise the in-app download.
- Do not weaken release security or expose model files through a
  production debug backdoor.

After setup, inference must work without Internet access.
Network use is allowed only for explicit model acquisition.

==================================================
6. QWEN CHAT FORMATTING AND CONTEXT
==================================================

Use the selected model's actual chat template and the capabilities of
the pinned llama.cpp revision.

Requirements:
- Correct system/user/assistant roles.
- Multi-turn history passed to the model.
- Correct end-of-turn and end-of-generation handling.
- Correct assistant generation prefix.
- No accidentally duplicated BOS or special tokens.
- No guessed prompt wrappers if the model template is available.

Default to non-thinking mode for the chat.
Use the model-supported mechanism rather than merely hiding reasoning.
Handle unexpected reasoning delimiters without corrupting output.

Initial conservative defaults:
- Context window: approximately 2048 tokens.
- Maximum response: approximately 256 tokens.
- Conservative sampling settings suitable for non-thinking Qwen chat.
- Bounded CPU thread count based on the device.

Verify valid settings against the model documentation.

Account for prompt tokens plus reserved output tokens.
When history exceeds the budget:
- Preserve the system instruction.
- Drop the oldest complete conversation turns.
- Never exceed native context bounds.
- Do not silently promise unlimited history.

Use a short Russian system instruction requesting clear answers in
the user's language.

Distinguish:
- Saved UI message history.
- Messages actually included in the current model context.

For cancelled/failed generations, ensure later prompts do not inherit
corrupt or ambiguous assistant turns.

==================================================
7. CHAT USER INTERFACE
==================================================

Implement a clean, functional Material 3 Compose interface.

Required:
- Russian UI.
- User and assistant message bubbles.
- Scrollable message history.
- Multiline input.
- Send button.
- Stop generation button.
- Incremental assistant response display.
- Model state: absent/downloading/loading/ready/generating/error.
- Model setup/import/download controls.
- New chat / clear conversation action.
- Error feedback and retry where appropriate.
- Keyboard/inset handling.
- Copy assistant response.
- Readable light/dark themes.

Behavior:
- Reject empty submissions.
- Disable incompatible actions while loading/generating.
- Avoid unexpectedly scrolling away when the user reads older messages.
- Do not lose UI state on normal recomposition.
- Handle activity recreation without starting duplicate generations.
- Do not silently continue generation after the process has died.
- Persist one conversation locally across app restarts.
- Mark interrupted responses honestly.
- A new chat clears conversation state, not the downloaded model.

A single-conversation MVP is sufficient.
Do not add a conversation browser, accounts, elaborate Markdown,
or unnecessary settings panels.

Keep responsibilities separated:
- Compose UI.
- ViewModel/state.
- Conversation persistence.
- Model acquisition/storage.
- Local inference engine.

Use existing project conventions.
Do not introduce a large DI/framework stack just for this task.

==================================================
8. BUILD AND TEST REQUIREMENTS
==================================================

Run the existing baseline checks first if practical.
After implementation, run:
- Debug build.
- Appropriate unit tests.
- Lint, with documented explanation for any remaining findings.
- Native builds for required ABIs.

Add meaningful automated tests for:
- Chat state transitions.
- Context/history truncation.
- Cancelled and failed response handling.
- Persistence serialization.
- Model download/file validation logic where practical.

Fake engines may be used for unit tests only.
The shipping chat must use real llama.cpp inference.
Do not substitute canned text or a remote API.

On the connected emulator, attempt:
1. Install/update the debug APK.
2. Launch the app.
3. Import/download or provision the real selected model.
4. Load the model.
5. Generate a nonempty answer to "Привет! Ответь одним предложением."
6. Send a follow-up requiring previous context.
7. Test cancellation and another generation afterward.
8. Check logs for crashes, ANR, and native errors.
9. Verify at least one real generation with networking disabled if
   this can be done safely without changing unrelated device settings.
10. Restore any temporary emulator network change afterward.

Prefer instrumentation/UI automation when available.
If you need a test harness for native inference, keep it in test/debug
code and explain its limits.

Set realistic timeouts for emulator inference.
Do not confuse slow generation with successful generation.
Do not claim UI flows were verified solely because a native smoke test
passed.

If full inference fails:
- Diagnose logs and actual error.
- Attempt bounded, evidence-based fixes.
- Do not hide failures or reduce the model to a fake.
- Report exactly which layer is working and what is blocked.

Distinguish emulator verification from physical-device verification.
Do not claim phone performance from emulator results.

==================================================
9. DISK SPACE, DEPENDENCIES, AND SCOPE CONTROL
==================================================

The system drive recently ran low on space.

Before large downloads/builds:
- Check available space.
- Consider host weights, device weights, SDK components, and native
  build intermediates separately.
- Avoid duplicate weight files.
- Do not delete unrelated caches or user files to make room.
- Do not move SDK, AVD, or system directories.
- Do not repeatedly download the model after unrelated build failures.

Keep .gitignore updated for:
- Model files and partial downloads.
- Native build outputs.
- Machine-specific configuration.
- Downloaded/generated binaries not intended for version control.

Do not ignore required source code or reproducibility files.
Preserve Gradle Wrapper and dependency lock/version conventions.

Do not add telemetry, API keys, hardcoded machine paths, or absolute
workstation-specific SDK locations to versioned files.

==================================================
10. DOCUMENTATION AND FINAL HANDOFF
==================================================

Update the project's existing state/handoff documents.

Document:
- Implemented architecture.
- llama.cpp pinned revision and integration method.
- Model source, artifact, size, verification, and license.
- Supported/tested ABIs.
- Required NDK/CMake versions.
- Build commands for Windows.
- Model download/import procedure.
- APK output location.
- Testing commands and actual results.
- Device/emulator used.
- Known limitations and remaining work.
- Storage and memory observations when measured.

Save a final report in docs/IMPLEMENTATION_REPORT.md or the project's
equivalent existing report location.

In the final Russian response include:
1. What actually works.
2. What was changed.
3. Build/test results.
4. Whether real Qwen inference was observed on Android.
5. Whether chat UI and offline behavior were actually verified.
6. Any blockers or unverified items.
7. Exact steps for the user to open and test the result.

Be explicit about:
- Implemented but not tested.
- Tested and passed.
- Failed or blocked.

Do not write "fully complete" if real Android inference was not verified.
Do not stop after a successful compile if runtime testing is available.
Do not ask whether to continue to the next approved phase.

Start by inspecting the project and executing this task.