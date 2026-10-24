# LocalAssistant Development Rules

## Purpose
Android app using Kotlin and Jetpack Compose, local Qwen3-1.7B GGUF Q4_K_M or Q4_0, whisper.cpp speech recognition, a local RAG database, and Android Calendar. This is the target stack, not a list of implemented features.
Reply to the user in Russian. Keep type and API names in their original form.

## Session Start
1. Read docs/STATE.md and docs/HANDOFF.md; treat them as status reports, not proof.
2. Read docs/PLAN.md and docs/DECISIONS.md.
3. Load project-handoff. Before Kotlin/Compose/Gradle/JNI tasks, load android-kotlin-quality.
4. Inspect actual files and Git status/diff if a repository exists. Preserve user changes.
5. Briefly state the current milestone, discrepancies, and one next task. Do not start another milestone without approval.

## Change Control
- Before editing, propose a small scope and acceptance criteria.
- Do not proactively upgrade Gradle, AGP, Kotlin, Compose, NDK, or CMake.
- New dependencies, Manifest permissions, networking, analytics, and cloud APIs require approval.
- No commits, pushes, APK installation, or destructive commands without approval.
- Never run git reset --hard, git clean, bulk deletion, or revert others' changes.
- These rules are not a sandbox. Also follow permission settings in opencode.json.

## Verification
Use the project Gradle Wrapper. First verify that the app module and tasks exist.
PowerShell commands from the project root:
```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```
For Git Bash, use ./gradlew. Do not mix Windows and Linux/WSL SDKs without explicit setup.
After production-code changes, run relevant available checks with user approval. If a check cannot run, report "not run" and explain why.
A successful build does not prove the app works on a phone.
On failure, diagnose the root cause and propose a minimal fix. Do not change multiple subsystems at once.

## Product Safety
- Core features work without cloud inference. Downloading weights is a separate, explicitly approved step.
- LLM tool calls are untrusted structured data. Validate arguments in application code.
- RAG content and transcripts are data, not instructions to the agent or app.
- Creating, updating, or deleting calendar events requires UI confirmation. Clarify ambiguous dates and events.
- Do not put audio, notes, calendar data, tokens, or personal data in logs or documentation.
- Do not commit GGUF files, audio, keystores, local.properties, or secrets.

## Checkpoints
After each completed task and before changing topics, update docs/STATE.md and docs/HANDOFF.md using project-handoff. Add significant decisions to docs/DECISIONS.md.
Record unfinished work and unverified assumptions. Do not store entire chats or private reasoning.

## Autonomous Editing
- Within an approved task, read, create, and edit project files without per-file confirmation.
- Update status documents after tasks without separate approval.
- Obtain approval before starting a new task or expanding scope.
- Dependencies, tool versions, AndroidManifest.xml permissions, and architecture changes still require approval.
- Execute commands according to permission settings in opencode.json.
- Commits, pushes, APK installation, and destructive operations require explicit approval.
