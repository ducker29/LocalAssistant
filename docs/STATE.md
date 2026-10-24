# Текущее состояние

## Статус
M0: критерии приёмки по `docs/PLAN.md` (аудит; чистая сборка, тесты и lint) выполнены — проверки успешно прошли 2026-10-24 в предыдущей сессии и остаются действительными историческими результатами. Повторный запуск в текущей сессии не требовался и не выполнялся.
Установка и запуск APK на устройстве/эмуляторе по-прежнему не проверены (в критерии M0 не входят).
Git-репозиторий инициализирован; коммитов не было до первого коммита в этой сессии.
2026-10-24 (текущая сессия): статус-документы сверены с фактическим состоянием.
Конфигурация агента подтверждена: действует английский `AGENTS.md`; оба проектных skill обнаружены и загружены через native skill tool.

## Фактический стек
- Корень проекта, Gradle Wrapper присутствует (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), Gradle 9.6.0 (bin, sha256 зафиксирован); daemon JVM 25 через foojay-resolver-convention 1.0.0.
- Модуль `:app` единственный (`settings.gradle.kts`, `rootProject.name = "LocalAssistant"`).
- AGP 9.4.1; Kotlin 2.2.10 в version catalog, подключён только `org.jetbrains.kotlin.plugin.compose`; отдельный `kotlin-android` не нужен — в AGP 9.x поддержка Kotlin встроенная.
- Version catalog: `gradle/libs.versions.toml`; Compose BOM 2026.02.01.
- namespace/applicationId: `com.example.localassistant` (шаблонное имя, не продуктовое).
- minSdk 26, targetSdk 37, compileSdk 37 (`compileSdk { version = release(37) }`).
- Java source/target 11; release-сборка включает optimization (AGP 9 DSL) с packageScope `androidx.**`, `kotlin.**`, `kotlinx.**`; правила R8 — `app/src/main/keepRules/rules.keep`.
- Зависимости: core-ktx 1.10.1, lifecycle-runtime-ktx 2.6.1, activity-compose 1.8.0, material3/ui/ui-tooling из BOM; junit 4.13.2, androidx.test.ext:junit 1.1.5, espresso 3.5.1.
- Manifest: одно activity `MainActivity`, разрешений нет, `allowBackup=true`; нативных библиотек нет.

## Среда (локально, путь не фиксируется в репозитории)
- `local.properties` указывает на существующий Android SDK; `ANDROID_HOME`/`ANDROID_SDK_ROOT` не заданы.
- SDK: platforms `android-36`, `android-37.0`; build-tools 36.0.0; platform-tools (adb); NDK 30.0.16248370, CMake 4.1.2 (установлены, проектом не используются).
- AVD: каталог `Medium_Phone.avd` + `Medium_Phone_API_37.0.ini`; устройство не подключалось, приложение не запускалось.
- JAVA_HOME — JBR Android Studio, `java -version` 25.0.3.
- build-tools 36.0.0 достаточно для compileSdk 37: сборка прошла без изменения `buildToolsVersion`.

## Git
- Репозиторий инициализирован 2026-10-24 (`git init`), ветка `master`; Git CLI 2.52.0.
- Коммитов нет; `git add`, `git commit`, `git push` не выполнялись.
- `.gitignore` дополнен: `.opencode/node_modules/`, keystore/секреты подписи, файлы моделей и аудио; удалено дублирующее `/local.properties` (оставлено `local.properties`).
- `.opencode/.gitignore` уже исключает `node_modules`, `package.json`, `package-lock.json`, `bun.lock` и сам себя; plugin-манифест в первый коммит не попадёт.
- `opencode.json` содержит только `$schema` и `permission`, секретов нет — можно включить в репозиторий.
- Кандидаты первого коммита: 48 файлов, включая `AGENTS.md`, `docs/`, `.opencode/skills/`, Gradle Wrapper и version catalog. `build/`, `.gradle/`, `node_modules`, `local.properties`, модели, аудио и keystore отсутствуют.
- 2026-10-24 перепроверено: рабочий каталог и корень репозитория совпадают (`E:/Programs/Android/AndroidStudioProjects/LocalAssistant`), ветка `master`, коммитов нет, индекс пуст, все 48 файлов untracked.
- `git check-ignore -v` подтвердил исключение: `app/build/` (через `app/.gitignore`), `/build`, `.gradle`, `.idea`, `local.properties`, `.opencode/node_modules`, `.opencode/package.json`, `.opencode/package-lock.json`, модели, аудио, keystore/подпись и файлы секретов.
- `SETUP.md` проверен на секреты: содержит только инструкции по установке, чувствительных данных нет.

## Реализовано
Только каркас шаблона Android Studio: `MainActivity` (`Scaffold` + `Greeting`), темы Compose, шаблонные unit/instrumented тесты. Модули LLM/STT/RAG/календаря отсутствуют.

## Конфигурация агента (2026-10-24, перечитано перед коммитом)
- Действует английская версия `AGENTS.md` в корне проекта; требование отвечать по-русски присутствует.
- `opencode.json` задаёт `read: allow`, `edit: allow`, `bash: allow`, `external_directory: ask`; блок `permission.skill` (`*: ask`, `project-handoff: allow`, `android-kotlin-quality: allow`) — намеренный.
- Значение `bash: allow` объясняет выполнение команд без запроса в этой сессии; `external_directory: ask` для этой сессии не проверялся.
- Skill `project-handoff` и `android-kotlin-quality` доступны в native skill tool и успешно загружены в текущей сессии.

## Проверки (2026-10-24, предыдущая сессия; исторические результаты, в текущей сессии не перезапускались)
| Команда | Результат | Длительность |
|---|---|---|
| `.\gradlew.bat :app:assembleDebug` | успех, 36 задач, configuration cache сохранён | 46.7 с |
| `.\gradlew.bat :app:testDebugUnitTest` | успех, 1 тест, 0 failures/errors | 12.5 с |
| `.\gradlew.bat :app:lintDebug` | успех, 15 warnings, 0 errors | 20.7 с |

- APK: `app/build/outputs/apk/debug/app-debug.apk`, 11.18 МБ, versionCode 1, versionName 1.0. Не устанавливался.
- Lint: 7 `UnusedResources`, 6 `GradleDependency`, 1 `RedundantLabel`, 1 `NewerVersionAvailable`; не исправлялись.
- Предупреждение `stripDebugDebugSymbols` для `libandroidx.graphics.path.so` не фатально.

## Блокеры и риски
- Шаблонный package/namespace `com.example` — переименование отдельное решение.
- Lint-замечания по версиям и неиспользуемым ресурсам остаются осознанно.
- `.opencode/package.json` и lock-файл не попадут в Git из-за вложенного `.opencode/.gitignore`; при необходимости версионировать plugin-зависимости это отдельное решение.

## Не проверено
- Установка и запуск APK на устройстве/эмуляторе.
- Instrumented-тесты (`connectedDebugAndroidTest`).
- Release-сборка и R8/minify.
- Фактическое поведение `external_directory: ask` в этой сессии.
- Работа LLM/STT/RAG/календаря — не реализовано.

## Следующий шаг
Первый локальный коммит создан в этой сессии. Затем согласовать проверку запуска на устройстве/эмуляторе и этап M1.
