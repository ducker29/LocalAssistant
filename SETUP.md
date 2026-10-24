# Setup

1. Wait for Gradle Sync to finish and exit the running OpenCode session.
2. Extract the archive into the Android project root, next to gradlew.bat. Do not add an extra parent folder.
3. If AGENTS.md, opencode.json, or matching documents already exist, back them up outside the repository and merge manually. Do not overwrite existing provider/model/mcp or permission settings without review.
4. If Git is not initialized, request approval before git init. Check .gitignore: exclude models, audio, local.properties, and secrets. Track AGENTS.md, docs/, and .opencode/skills/. Refine any blanket .opencode/ exclusion so skills remain trackable.
5. Open the Studio terminal in the project root. Run opencode --version, then opencode.
6. Send the initial prompt provided in the chat instructions. Skill names are not slash commands: ask the agent to load each skill by name.
7. Review command and write approval requests; initially approve once. Do not enable --auto.
8. Audit and verify the clean project first. Do not integrate native libraries yet.

## Daily Workflow
Start: read status -> compare with code -> choose one task.
Work: small diff -> checks -> report -> checkpoint.
Finish: request updates to STATE.md and HANDOFF.md; review their diff.
Resume: reread status documents even when continuing an existing conversation.

## Sources
OpenCode Skills: https://opencode.ai/docs/skills/
OpenCode Rules: https://opencode.ai/docs/rules/
OpenCode Permissions: https://opencode.ai/docs/permissions/
Status documents are a project convention, not a built-in automatic memory mechanism.
