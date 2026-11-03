# Встроенная embedding-модель

Дата: 2026-11-02. Пользователь явно разрешил включить проверенный posfix GGUF в APK.
Текущий статус: generated asset исправлен, assembleDebug/4 targeted tests/lintDebug/ZIP hash PASS.

## Добавлено

- app/build.gradle.kts: PrepareBundledEmbedding с @InputFile/@OutputDirectory, size/SHA256
  guard, input models/multilingual-e5-small-q8_0-posfix.gguf, generated assets в build/.
  Ни веса в Git, ни дублированный binary в app/src/main/assets не добавлялись.
- app/src/main/assets/embedding/NOTICE.txt: MIT notice, provenance и positional repair.
- BundledE5Model: фоновые IO, проверка существующего файла, временный app-owned файл,
  проверка streamed size/hash, fd.sync и same-directory atomic move; при ошибке/отмене
  предыдущий target остаётся. Проверенные веса при повторном открытии переиспользуются.
- DocumentSearchService получает AssetManager приложения; preparation serialized с
  import/index/search и native unload. Model RAM не загружается самой подготовкой файла.
- DocumentsViewModel запускает подготовку на первом открытии; статус виден в loading UI.
  Даже ошибка подготовки не блокирует загрузку списка документов; manual import fallback.
- DocumentToolsPanel сообщает «Встроенная модель поиска готова», ручной импорт не основной.
- Добавлены4 BundledE5ModelTest: copy/reuse, corrupt-target recovery, invalid-asset rollback,
  cancellation rollback. Пока НЕ запускались.

## Проверки и остановка

Пользователь отдельно разрешил assembleDebug + только BundledE5ModelTest + lintDebug
и проверку size/SHA256 asset непосредственно в APK.
Команда: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests "com.example.localassistant.embedding.BundledE5ModelTest" :app:lintDebug`
JAVA_HOME=E:\Programs\Android\Android Studio\jbr.

Результат: BUILD FAILED7с на configure app/build.gradle.kts:65.
AGP запрещает Provider в sourceSets.assets.srcDir, так как generated/static надо различать
через Variant Sources API. Task не выполнялся; compile/tests/lint/package ещё не запускались.
Новый APK со встроенными весами НЕ собран, старый APK не выдавать за новый. Device не трогали.

После остановки пользователь разрешил fix/повтор. SourceSet Provider registration удалён,
generated assets зарегистрированы через androidComponents.onVariants /
variant.sources.assets.addGeneratedSourceDirectory с PrepareBundledEmbedding::assetDirectory.
Ручной preBuild dependency больше не нужен: задача связана через Variant API.
Versions/dependencies/validation guards сохранены; gradle.properties не менялся.
Повтор той же команды — BUILD SUCCESSFUL1m5s: prepareBundledEmbedding/assembleDebug,
4 BundledE5ModelTest и lintDebug PASS. Конфигурационный кеш сохранён.
Python zipfile проверил actual APK entry size/hash и наличие NOTICE: PASS.

## Проверенный APK

- app/build/outputs/apk/debug/app-debug.apk:172267453 bytes (164.29MiB).
- assets/embedding/multilingual-e5-small-q8_0-posfix.gguf:132440544 bytes,
  ZIP compressed123531529 bytes, SHA256 совпал с pinned input.
- NOTICE.txt присутствует в APK. Бинарник остаётся generated/ignored, не добавлен в Git.
- По прямому запросу эта сборка установлена на CPH2581 через adb install -r — Success,
  без очистки данных. Android extraction/reuse ещё не приняты пользователем.

## Размер/приёмка

Input asset132440544 bytes; hash bdb785b2ca8532ff3097f93ac3e4e1d560a122cfba377bf559895aebfd9be6d9.
APK увеличивается на модель с поправкой на ZIP compression; приватная копия требует ещё
около126.3MiB. Нужна ручная проверка первого открытия, обновления/reuse и индексации.
Установка затем прямо разрешена и выполнена; ручная приёмка впереди.
