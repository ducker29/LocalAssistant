package com.example.localassistant.calendar

import java.time.ZonedDateTime

/** Stateless interpretation/grounding. Session remains the sole owner of task/object state. */
internal object CalendarTurnPolicy {
    private val quotes = Regex("«[^»]*»|\"[^\"]*\"|“[^”]*”")
    private val commands = Regex(
        "(?:^|[,;.!?]\\s*|\\b(?:и|затем|потом)\\s+)(?:пожалуйста\\s+)?" +
            "(?:(?:можешь|можете|прошу|хочу|нужно|надо|давай)\\s+)?" +
            "(найди(?:те)?|найти|покажи(?:те)?|показать|создай(?:те)?|создать|добавь(?:те)?|добавить|" +
            "запланируй(?:те)?|запланировать|перенеси(?:те)?|перенести|измени(?:те)?|изменить|" +
            "переименуй(?:те)?|поменяй(?:те)?|очисти(?:те)?|удали(?:те)?|удалить|дай(?:те)?|" +
            "заверши(?:те)?|завершить|выполни(?:те)?|выполнить|отметь(?:те)?|отметить|верни(?:те)?|вернуть)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val reference = Regex("\\b(их|его|ее|это событие|эти события|эту задачу|эти задачи|найденн[а-я]*|выбранн[а-я]*)\\b")
    private val calendarWords = Regex("событи|задач|встреч|созвон|календар|расписани|завтра|сегодня|недел|планерк|совещан|собрани|трениров")
    private val general = Regex("^(расскажи|объясни|переведи|напиши|привет|спасибо|что такое|как работает|почему)\\b")
    private val cancel = Regex("^(отмена|отмени|забудь|стоп|передумал[а-я]*|не надо|не удаляй|не создавай)\\b")

    fun prepare(session: CalendarSession, text: String, now: ZonedDateTime): CalendarTurnPlan {
        val original = text.trim()
        val quoteRanges = quotes.findAll(original).map { it.range }.toList()
        val rename = CalendarRenameExpression.find(original, quoteRanges)
        val listExpression = Regex("\\b(?:в|из|по)\\s+спис(?:ке|ка|ок)\\s+(«[^»]+»|\"[^\"]+\"|[\\p{L}\\p{N}_-]+)", RegexOption.IGNORE_CASE)
            .findAll(original).firstOrNull { candidate -> quoteRanges.none { candidate.range.first in it } }
        val details = Regex("\\b(описание|место|адрес)\\s*[:—-]\\s*(.+?)(?=\\s+\\b(?:описание|место|адрес)\\s*[:—-]|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(original)
            .filter { rename == null || !overlaps(it.range, rename.valueRange) }.toList()
        val masked = quotes.replace(original) { " ".repeat(it.value.length) }.mapIndexed { i, c ->
            if (details.any { i in it.range } || rename?.valueRange?.contains(i) == true || listExpression?.range?.contains(i) == true) ' ' else c
        }.joinToString("")
        val normalized = normalize(masked)
        val temporal = CalendarTemporalExpression.find(masked) ?: CalendarTemporalExpression.quantityReply(
            masked, session.draft?.sourceDate ?: session.draft?.date ?:
                (session.pendingSubject?.values?.get(CalendarField.DATE) as? CalendarFieldValue.Text)?.value,
        )
        val calendarTopic = calendarWords.containsMatchIn(normalized) || temporal != null || listExpression != null
        fun plan(kind: CalendarTurnTransition, op: CalendarOp? = null, seed: CalendarFieldPatch = CalendarFieldPatch(),
                 all: Boolean? = null, message: String? = null) = CalendarTurnPlan(
            session.taskId, session.version, session.plannerContext(), original, kind, op, seed, all, message,
        )
        if (cancel.containsMatchIn(normalized)) return plan(CalendarTurnTransition.CANCEL)
        if (rename?.ambiguous == true) return plan(CalendarTurnTransition.CLARIFY, message =
            "Укажите названия в кавычках, например: измени название «Старое» на «Новое».")
            .copy(resetOnClarify = true)
        val newSubject = Regex("^(?:(?:а|теперь|тогда)\\s+)*(?:другое событие|другая задача|другую задачу|новое событие|новая задача)\\b").find(normalized)
        val matches = commands.findAll(masked).toList()
        val ops = matches.map { commandOperation(normalize(it.groupValues[1])) }
        val clears = clearFields(normalized)
        val writes = ops.filter { it != CalendarOp.READ }.toSet().let {
            if (clears.isNotEmpty()) (it - CalendarOp.DELETE) + CalendarOp.UPDATE else it
        }
        if (writes.size > 1) return plan(CalendarTurnTransition.CLARIFY, message = "Уточните одно действие: создание, изменение или удаление.")
            .copy(resetOnClarify = true)
        val question = Regex("^(есть(?: ли)?|что|какие|мои|расписание|список|списки)\\b").containsMatchIn(normalized) && calendarTopic
        val temporalFollowup = session.draft == null && session.selector != null &&
            CalendarTemporalExpression.isOnlyExpression(normalized)
        val explicit = writes.firstOrNull() ?: CalendarOp.READ.takeIf { ops.isNotEmpty() || question || temporalFollowup }
        val tasks = Regex("\\bзадач[а-я]*\\b").containsMatchIn(normalized)
        val events = Regex("\\b(?:событи[а-я]*|мероприяти[а-я]*)\\b").containsMatchIn(normalized)
        val mixed = Regex("(?:задач[а-я]*\\s+и\\s+событи[а-я]*|событи[а-я]*\\s+и\\s+задач[а-я]*)").containsMatchIn(normalized)
        val typeWordIsTitleReply = explicit == null && newSubject == null && CalendarField.TITLE in session.awaiting &&
            !Regex("\\b(?:именно|только|это)\\s+(?:задач|событи)").containsMatchIn(normalized)
        if (mixed && !typeWordIsTitleReply) return plan(CalendarTurnTransition.CLARIFY, message = "Уточните один тип: события Calendar или задачи Google Tasks.").copy(resetOnClarify = true)
        // References must identify the object, not a possessive pronoun buried in a new title.
        val combinedLookup = ops.any { it == CalendarOp.READ } && writes.isNotEmpty()
        val referenceArgument = Regex("^(?:(?:мне|пожалуйста)\\s+)*(?:их|его|ее|это событие|эти события|эту задачу|эти задачи|найденн[а-я]*|выбранн[а-я]*)\\b")
        val hasRef = !combinedLookup && (reference.matches(normalized) ||
            matches.any { referenceArgument.containsMatchIn(normalize(masked.substring(it.range.last + 1))) } ||
            rename?.source?.let { reference.matches(normalize(it)) } == true)
        val correction = Regex("\\b(время|дат[ау]|названи[ея]|описание|место)\\b").containsMatchIn(normalized)
        val fieldReference = explicit == CalendarOp.UPDATE && ((rename != null && rename.source.isBlank()) ||
            Regex("^(?:измени|поменяй|очисти|переименуй)\\s+(?:его\\s+)?(?:время|дату|название|описание|место)\\s*(?:на\\b|в\\b|:|$)").containsMatchIn(normalized))
        val draftFieldEdit = explicit == CalendarOp.UPDATE && session.draft?.op == CalendarOp.CREATE && correction &&
            (rename == null || rename.source.isBlank() || reference.matches(normalize(rename.source)))
        val inheritSource = (explicit == null && (session.draft != null || session.pendingSubject != null)) ||
            (hasRef && (session.targets.isNotEmpty() || session.pendingSubject != null)) ||
            (fieldReference && session.targets.isNotEmpty()) || temporalFollowup || draftFieldEdit
        // A new untyped request always starts in Calendar Events, never the previous provider.
        val objectKind = when {
            tasks && !events && !typeWordIsTitleReply -> CalendarObjectKind.TASK
            events && !typeWordIsTitleReply -> CalendarObjectKind.EVENT
            inheritSource -> session.objectKind ?: CalendarObjectKind.EVENT
            else -> CalendarObjectKind.EVENT
        }
        if (explicit != null && !calendarTopic && session.objectKind == null && session.draft == null && session.targets.isEmpty() && session.pendingSubject == null) {
            return plan(CalendarTurnTransition.GENERAL)
        }
        if (explicit == null && general.containsMatchIn(normalized)) return plan(CalendarTurnTransition.GENERAL)
        val subjectOnly = explicit == null && (newSubject != null || session.pendingSubject != null)
        val continuingDraft = !subjectOnly && session.draft != null && session.objectKind == objectKind && (explicit == null ||
            (explicit == CalendarOp.UPDATE && session.draft?.op == CalendarOp.CREATE && correction &&
                (rename == null || rename.source.isBlank() || reference.matches(normalize(rename.source)))))
        if (explicit == null && !continuingDraft && !subjectOnly) {
            return if (hasRef || calendarTopic) plan(CalendarTurnTransition.CLARIFY,
                message = "Что сделать с записью: найти, создать, изменить или удалить?")
            else plan(CalendarTurnTransition.GENERAL)
        }
        val operation = if (subjectOnly) CalendarOp.CREATE else if (continuingDraft) requireNotNull(session.draft).op else requireNotNull(explicit)
        if (objectKind == CalendarObjectKind.EVENT && matches.any { Regex("^(заверш|выполн|верн)").containsMatchIn(normalize(it.groupValues[1])) }) {
            return plan(CalendarTurnTransition.CLARIFY, message = "Отметка выполнения относится к Google Tasks. У событий такого статуса нет.")
        }
        val kind = when {
            subjectOnly -> CalendarTurnTransition.NEW_OBJECT
            continuingDraft -> CalendarTurnTransition.CONTINUE
            ((hasRef || fieldReference) && session.targets.isNotEmpty() || temporalFollowup) && operation != CalendarOp.CREATE -> CalendarTurnTransition.TARGETS
            hasRef && operation != CalendarOp.CREATE && session.pendingSubject == null -> return plan(CalendarTurnTransition.CLARIFY,
                message = "Нет выбранных записей. Сначала найдите их или укажите название и день.")
            else -> CalendarTurnTransition.NEW
        }
        if (kind == CalendarTurnTransition.TARGETS && session.objectKind != objectKind) return plan(CalendarTurnTransition.CLARIFY,
            message = "Выбранные записи другого типа. Найдите нужные задачи или события отдельно.").copy(resetOnClarify = true)
        if (objectKind == CalendarObjectKind.EVENT && listExpression != null) return plan(CalendarTurnTransition.CLARIFY,
            message = "Список относится к Google Tasks. Для задачи явно укажите слово «задача»; события не помещаются в списки Tasks.").copy(resetOnClarify = true)
        if (objectKind == CalendarObjectKind.TASK && explicit in setOf(CalendarOp.CREATE, CalendarOp.UPDATE, CalendarOp.DELETE) &&
            Regex("\\bспис(?:ок|ки)\\s+задач\\b").containsMatchIn(normalized)) return plan(CalendarTurnTransition.CLARIFY,
                message = "Пока доступен просмотр и выбор списков. Управление самими списками выполняйте в Google Tasks.").copy(resetOnClarify = true)
        val listsOnly = objectKind == CalendarObjectKind.TASK && operation == CalendarOp.READ && Regex("\\bсписки\\s+задач\\b").containsMatchIn(normalized)
        if (objectKind == CalendarObjectKind.TASK && operation == CalendarOp.UPDATE &&
            listExpression?.value?.let { Regex("^в\\s+список\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true &&
            matches.any { normalize(it.groupValues[1]).startsWith("перенес") }) return plan(CalendarTurnTransition.CLARIFY,
                message = "Перемещение между списками пока не поддержано. Можно изменить дату, название, описание или статус задачи.").copy(resetOnClarify = true)
        val values = mutableMapOf<CalendarField, CalendarFieldValue>()
        val spans = matches.mapTo(mutableListOf()) { it.range }.apply { addAll(details.map { it.range }) }
        spans.addAll(Regex("\\bпожалуйста\\b", RegexOption.IGNORE_CASE).findAll(masked).map { it.range }.toList())
        newSubject?.let { spans += it.range }
        rename?.let { spans += it.valueRange; it.labelRange?.let { range -> spans += range } }
        listExpression?.let { spans += it.range }
        fun put(field: CalendarField, value: String) { values[field] = CalendarFieldValue.Text(value) }
        listExpression?.let { put(CalendarField.LIST, it.groupValues[1].trim('«', '»', '"')) }
        var statusFilter: GoogleTaskStatus? = null
        if (objectKind == CalendarObjectKind.TASK) {
            val stableText = masked.lowercase(java.util.Locale.forLanguageTag("ru")).replace('ё', 'е')
            val statusWords = Regex("\\b(?:как\\s+)?(?:не\\s+)?(?:невыполнен[а-я]*|незавершен[а-я]*|выполнен[а-я]*|завершен[а-я]*|готов[а-я]*)\\b")
            val statusMatches = statusWords.findAll(stableText).toList()
            fun state(value: String) = if (Regex("невыполн|незаверш|\\bне\\s+").containsMatchIn(value)) GoogleTaskStatus.NEEDS_ACTION else GoogleTaskStatus.COMPLETED
            val status = when {
                matches.any { normalize(it.groupValues[1]).startsWith("верн") } -> GoogleTaskStatus.NEEDS_ACTION
                matches.any { Regex("^(заверш|выполн)").containsMatchIn(normalize(it.groupValues[1])) } -> GoogleTaskStatus.COMPLETED
                else -> statusMatches.lastOrNull()?.value?.let(::state)
            }
            spans.addAll(statusMatches.map { it.range })
            Regex("\\b(?:в работу|к выполнению)\\b").find(normalized)?.let { spans += it.range }
            if (operation in setOf(CalendarOp.UPDATE, CalendarOp.CREATE) && status != null) put(CalendarField.STATUS, status.wire)
            if (operation in setOf(CalendarOp.READ, CalendarOp.SEARCH)) statusFilter = statusMatches.map { state(it.value) }.distinct().singleOrNull()
        }
        details.forEach {
            val value = it.groupValues[2].trim().trim('«', '»', '"', '“', '”')
            if (value.isNotBlank()) {
                val field = if (operation in setOf(CalendarOp.CREATE, CalendarOp.UPDATE)) {
                    if (normalize(it.groupValues[1]) == "описание") CalendarField.DESCRIPTION else CalendarField.LOCATION
                } else CalendarField.QUERY
                put(field, value)
            }
        }
        var dateFound = false
        fun date(value: String, range: IntRange, destination: Boolean = false) {
            if (dateFound) return
            dateFound = true
            val field = when {
                operation == CalendarOp.CREATE -> CalendarField.DATE
                operation == CalendarOp.UPDATE && (destination || (kind == CalendarTurnTransition.CONTINUE &&
                    CalendarField.DATE in session.awaiting && CalendarField.SOURCE_DATE !in session.awaiting && session.targets.isNotEmpty())) -> CalendarField.DATE
                operation == CalendarOp.UPDATE || operation == CalendarOp.DELETE -> CalendarField.SOURCE_DATE
                else -> CalendarField.DATE
            }
            put(field, value)
            spans += range
        }
        temporal?.let {
            date(it.reference, it.range, destination = rename == null && !it.period && it.destination)
            spans.addAll(it.extraRanges)
        }
        if (!dateFound && CalendarField.DATE in session.awaiting && normalized.matches(Regex("\\d{1,2}"))) {
            date("day:$normalized", original.indices)
        }
        val shift = Regex("\\bс\\s+(\\d{1,2}(?::\\d{2})?)\\s*(?:час(?:а|ов)?\\s+)?на\\s+(\\d{1,2}(?::\\d{2})?)(?:\\s*час(?:а|ов)?)?\\b", RegexOption.IGNORE_CASE).find(masked)
        fun clock(raw: String): String? {
            val parts = raw.split(':')
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return if (h in 0..23 && m in 0..59) "%02d:%02d".format(java.util.Locale.ROOT, h, m) else null
        }
        if (shift != null && operation == CalendarOp.UPDATE) {
            clock(shift.groupValues[1])?.let { put(CalendarField.SOURCE_TIME, it) }
            clock(shift.groupValues[2])?.let { put(CalendarField.TIME, it) }
            spans += shift.range
        }
        val duration = Regex("\\b(?:на|длительностью|продолжительностью|длительность)\\s+(\\d+)\\s*(минут[а-я]*|час[а-я]*|дн[а-я]*)", RegexOption.IGNORE_CASE).find(masked)
        if (duration != null && (operation == CalendarOp.CREATE || normalize(duration.value).startsWith("длитель"))) {
            val unit = when { normalize(duration.groupValues[2]).startsWith("мин") -> "m"; normalize(duration.groupValues[2]).startsWith("час") -> "h"; else -> "d" }
            put(CalendarField.DURATION, duration.groupValues[1] + unit)
            spans += duration.range
        } else if (duration != null && operation == CalendarOp.UPDATE && shift == null) {
            return plan(CalendarTurnTransition.CLARIFY, message = "Уточните точное новое время, например «на 16:00».")
        }
        val clockPattern = Regex("\\b(?:(?:в|на|к|время)\\s*[-—:]?\\s*)?(\\d{1,2})(?::(\\d{2}))?(?:\\s*час(?:а|ов)?(?:\\s*(утра|вечера|дня|ночи))?)?\\b", RegexOption.IGNORE_CASE)
        for (match in clockPattern.findAll(masked)) {
            if (spans.any { overlaps(it, match.range) }) continue
            val explicitTime = match.value.contains(':') || Regex("час|утра|вечера|дня|ночи|^(в|на|к|время)\\s", RegexOption.IGNORE_CASE).containsMatchIn(match.value)
            val expected = CalendarField.TIME in session.awaiting && CalendarField.DATE !in session.awaiting
            if (!explicitTime && !(expected && normalize(match.value) == normalized)) continue
            var h = match.groupValues[1].toIntOrNull() ?: continue
            val part = normalize(match.groupValues[3])
            if (part in setOf("вечера", "дня") && h in 1..11) h += 12
            if (part == "ночи" && h == 12) h = 0
            val value = clock("$h:${match.groupValues[2].ifBlank { "00" }}") ?: continue
            val field = if (operation in setOf(CalendarOp.CREATE, CalendarOp.UPDATE)) CalendarField.TIME else CalendarField.SOURCE_TIME
            if (field !in values) put(field, value)
            spans += match.range
        }
        Regex("(?:на|за)\\s+(\\d+)\\s+дн[а-я]*", RegexOption.IGNORE_CASE).find(masked)?.let {
            val count = it.groupValues[1].toIntOrNull()
            if (count != null && count in 1..90 && operation in setOf(CalendarOp.READ, CalendarOp.SEARCH, CalendarOp.DELETE)) {
                values[CalendarField.DAYS] = CalendarFieldValue.Number(count)
                spans += it.range
            }
        }
        if (Regex("весь день|целый день|без времени").containsMatchIn(normalized)) {
            values[CalendarField.ALL_DAY] = CalendarFieldValue.Flag(true)
        } else if (CalendarField.TIME in values && session.draft?.allDay == true && operation == CalendarOp.CREATE) {
            values[CalendarField.ALL_DAY] = CalendarFieldValue.Flag(false)
        }
        var content = removeSpans(original, spans).trim()
        content = content.replace(Regex("\\b(?:весь день|целый день|без времени)\\b", RegexOption.IGNORE_CASE), " ")
        val prefix = if (operation == CalendarOp.CREATE && kind == CalendarTurnTransition.CONTINUE && explicit == null && CalendarField.TITLE in session.awaiting) "^$"
            else if (operation == CalendarOp.CREATE) "^(?:(?:мне|пожалуйста)\\b\\s*)*(?:(?:новую|новое)\\s+)?(?:(?:задачу|задача|событие|мероприятие)\\b\\s*)?"
            else "^(?:(?:мне|пожалуйста|мои|все|всех|что|какие|есть|ли|у меня|в календаре|список|именно|только)\\b\\s*)*" +
                "(?:(?:событи(?:е|я|й|ю|ям|ями|ях)|задач(?:а|у|и|е|ей|ами|ах)?|мероприят(?:ие|ия|ий|ии|ию)|расписание)\\b\\s*)?"
        content = content.replace(Regex(prefix, RegexOption.IGNORE_CASE), "")
        if (operation != CalendarOp.CREATE || (session.pendingSubject != null && reference.matches(normalize(content)))) {
            content = content.replace(Regex("\\s*(?:и\\s+)?(?:их|его|ее|её|это событие|эти события|все|всех|в календаре)\\s*$", RegexOption.IGNORE_CASE), "")
        }
        content = content.replace(Regex("\\s+"), " ").trim(' ', ',', '.', '?', '!', ':', '—', '-')
        val quoted = quotes.findAll(original).firstOrNull { quote -> details.none { overlaps(quote.range, it.range) } &&
            (listExpression == null || !overlaps(quote.range, listExpression.range)) &&
            (rename == null || !overlaps(quote.range, rename.valueRange)) }
            ?.value?.trim('«', '»', '"', '“', '”')
        if (rename != null && operation in setOf(CalendarOp.CREATE, CalendarOp.UPDATE)) {
            rename.title?.let { put(CalendarField.TITLE, it) }
            val source = content.replace(Regex("^(?:его|ее|её|это событие|эти события)$", RegexOption.IGNORE_CASE), "").trim()
            if (operation == CalendarOp.UPDATE && source.isNotBlank()) put(CalendarField.QUERY, quoted ?: source)
        } else when {
            operation == CalendarOp.CREATE && (kind == CalendarTurnTransition.NEW ||
                (kind == CalendarTurnTransition.NEW_OBJECT && (newSubject != null || session.pendingSubject?.values?.containsKey(CalendarField.TITLE) != true)) ||
                CalendarField.TITLE in session.awaiting) -> {
                val title = quoted ?: content
                val limit = if (objectKind == CalendarObjectKind.TASK) CalendarToolRequest.MAX_TASK_TITLE else CalendarToolRequest.MAX_TITLE
                if (title.isNotBlank() && title.length <= limit) put(CalendarField.TITLE, title)
            }
            kind == CalendarTurnTransition.NEW && content.isNotBlank() -> {
                val detail = (values[CalendarField.QUERY] as? CalendarFieldValue.Text)?.value
                put(CalendarField.QUERY, listOfNotNull(quoted ?: content, detail).joinToString(" "))
            }
            kind == CalendarTurnTransition.CONTINUE && CalendarField.QUERY in session.awaiting && content.isNotBlank() -> put(CalendarField.QUERY, quoted ?: content)
            kind == CalendarTurnTransition.CONTINUE && CalendarField.LIST in session.awaiting && content.isNotBlank() -> put(CalendarField.LIST, quoted ?: content)
            kind == CalendarTurnTransition.TARGETS && operation in setOf(CalendarOp.UPDATE, CalendarOp.DELETE) && !fieldReference -> {
                val constraint = content.replace(Regex("^(?:(?:найденн[а-я]*|выбранн[а-я]*|эти|это|ее|её|его|их|события|событие)\\b\\s*)+", RegexOption.IGNORE_CASE), "").trim()
                if (constraint.isNotBlank()) put(CalendarField.QUERY, quoted ?: constraint)
            }
        }
        if (listsOnly) values.remove(CalendarField.QUERY)
        val taskClears = if (objectKind == CalendarObjectKind.TASK && Regex("без времени|не нужно время").containsMatchIn(normalized)) {
            values.remove(CalendarField.ALL_DAY)
            setOf(CalendarField.TIME, CalendarField.END_TIME, CalendarField.DURATION, CalendarField.SOURCE_TIME, CalendarField.ALL_DAY)
        } else emptySet()
        if (objectKind == CalendarObjectKind.TASK) values.remove(CalendarField.ALL_DAY)
        val finalOp = if (operation == CalendarOp.READ && CalendarField.QUERY in values) CalendarOp.SEARCH else operation
        val all = Regex("\\b(все|всех|их|оба|обе|найденные|выбранные|эти события)\\b").containsMatchIn(normalized).takeIf {
            explicit != null || Regex("\\b(все|всех|их|только одно|один)\\b").containsMatchIn(normalized)
        }
        val subject = session.pendingSubject
        val useSubject = subject != null && newSubject == null && kind in setOf(CalendarTurnTransition.NEW, CalendarTurnTransition.NEW_OBJECT) &&
            (kind == CalendarTurnTransition.NEW_OBJECT || (CalendarField.TITLE !in values && CalendarField.QUERY !in values))
        val inherited = if (useSubject && !subjectOnly) requireNotNull(subject).values.mapKeys { (field, _) ->
            when {
                field == CalendarField.TITLE && operation != CalendarOp.CREATE -> CalendarField.QUERY
                field == CalendarField.DATE && operation in setOf(CalendarOp.UPDATE, CalendarOp.DELETE) -> CalendarField.SOURCE_DATE
                field == CalendarField.TIME && operation != CalendarOp.CREATE -> CalendarField.SOURCE_TIME
                else -> field
            }
        } else emptyMap()
        return plan(kind, finalOp.takeUnless { subjectOnly }, CalendarFieldPatch((inherited + values).filterKeys { it !in taskClears }, clears + taskClears), all)
            .copy(usesPendingSubject = useSubject, titleQuery = rename != null && operation == CalendarOp.UPDATE,
                objectKind = objectKind, taskListsOnly = listsOnly, taskStatusFilter = statusFilter)
    }

    /** Only current-turn, text-backed suggestions may supplement the app-extracted fields. */
    fun ground(plan: CalendarTurnPlan, proposal: CalendarModelPatch?): CalendarFieldPatch {
        if (proposal == null || proposal.taskId != plan.taskId || proposal.version != plan.version) return plan.seed
        val text = normalize(plan.text)
        val accepted = proposal.patch.values.filter { (field, value) ->
            field !in plan.seed.values && field !in plan.seed.clear && value is CalendarFieldValue.Text &&
                field in setOf(CalendarField.TITLE, CalendarField.DESCRIPTION, CalendarField.LOCATION) &&
                normalize(value.value).length >= 2 && text.contains(normalize(value.value)) &&
                (field != CalendarField.TITLE || (plan.operation in setOf(CalendarOp.CREATE, CalendarOp.UPDATE) && Regex("названи|переимен").containsMatchIn(text))) &&
                (field !in setOf(CalendarField.DESCRIPTION, CalendarField.LOCATION) || plan.operation in setOf(CalendarOp.CREATE, CalendarOp.UPDATE)) &&
                (field != CalendarField.DESCRIPTION || text.contains("описан")) &&
                (field != CalendarField.LOCATION || text.contains("место") || text.contains("адрес"))
        }
        return CalendarFieldPatch(accepted + plan.seed.values, plan.seed.clear)
    }

    private fun commandOperation(verb: String) = when {
        verb.startsWith("удал") -> CalendarOp.DELETE
        verb.startsWith("созда") || verb.startsWith("добав") || verb.startsWith("заплан") -> CalendarOp.CREATE
        verb.startsWith("най") || verb.startsWith("покаж") || verb.startsWith("показ") || verb.startsWith("дай") -> CalendarOp.READ
        else -> CalendarOp.UPDATE
    }
    private fun clearFields(text: String): Set<CalendarField> {
        val field = Regex("(?:очисти(?:те)?|убери(?:те)?|сотри(?:те)?|удали(?:те)?)\\s+(описание|место|время|дату|название)\\b").find(text)?.groupValues?.get(1)
        return setOfNotNull(when (field) {
            "описание" -> CalendarField.DESCRIPTION; "место" -> CalendarField.LOCATION
            "время" -> CalendarField.TIME; "дату" -> CalendarField.DATE; "название" -> CalendarField.TITLE
            else -> null
        })
    }
    private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last
    private fun removeSpans(text: String, ranges: List<IntRange>): String = text.mapIndexed { i, c -> if (ranges.any { i in it }) ' ' else c }.joinToString("")
    private fun normalize(text: String) = text.lowercase(java.util.Locale.forLanguageTag("ru")).replace('ё', 'е').trim()
}
