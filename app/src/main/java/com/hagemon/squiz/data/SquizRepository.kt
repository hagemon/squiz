package com.hagemon.squiz.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.hagemon.squiz.data.local.FolderEntity
import com.hagemon.squiz.data.local.GuessedQuestionEntity
import com.hagemon.squiz.data.local.OptionEntity
import com.hagemon.squiz.data.local.PracticeAnswerEntity
import com.hagemon.squiz.data.local.PracticeSessionEntity
import com.hagemon.squiz.data.local.QuestionBankEntity
import com.hagemon.squiz.data.local.QuestionEntity
import com.hagemon.squiz.data.local.QuestionWithOptions
import com.hagemon.squiz.data.local.SessionQuestionEntity
import com.hagemon.squiz.data.local.SessionQuestionWithOptions
import com.hagemon.squiz.data.local.SquizDatabase
import com.hagemon.squiz.data.security.ApiKeyStore
import com.hagemon.squiz.model.AppSettings
import com.hagemon.squiz.model.BankPracticeSettings
import com.hagemon.squiz.model.ColorTheme
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.LibraryItemType
import com.hagemon.squiz.model.LlmConfig
import com.hagemon.squiz.model.LlmProvider
import com.hagemon.squiz.model.ParsedQuestion
import com.hagemon.squiz.model.PracticeAnswerSnapshot
import com.hagemon.squiz.model.PracticeMode
import com.hagemon.squiz.model.PracticeSession
import com.hagemon.squiz.model.QuestionOption
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.QuizQuestion
import com.hagemon.squiz.model.ThemeMode
import com.hagemon.squiz.model.activityAt
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private val Context.squizDataStore by preferencesDataStore(name = "squiz_preferences")

data class QuestionBankMergeResult(
    val overlappingQuestions: Int,
    val addedQuestions: Int,
    val mergedSessions: Int,
    val mergedAnswers: Int,
    val mergedWrongAnswers: Int,
    val mergedGuessedQuestions: Int,
) {
    val changed: Boolean
        get() = addedQuestions > 0 || mergedSessions > 0 || mergedAnswers > 0 || mergedGuessedQuestions > 0
}

class SquizRepository(context: Context) {
    private val dataStore = context.applicationContext.squizDataStore
    private val apiKeyStore = ApiKeyStore(context)
    private val database = SquizDatabase.get(context)
    private val dao = database.dao()

    val settings: Flow<AppSettings> = dataStore.safeData.map { preferences ->
        AppSettings(
            themeMode = preferences[Keys.themeMode].toEnumOrDefault(ThemeMode.SYSTEM),
            colorTheme = preferences[Keys.colorTheme].toEnumOrDefault(ColorTheme.LAVENDER),
            textScale = (preferences[Keys.textScale] ?: 1f).coerceIn(0.8f, 1.3f),
            singleChoiceAutoNext = preferences[Keys.singleChoiceAutoNext] ?: false,
            llmConfig = LlmConfig(
                provider = preferences[Keys.llmProvider].toEnumOrDefault(LlmProvider.OPENAI_COMPATIBLE),
                endpointUrl = preferences[Keys.llmEndpointUrl]
                    ?: LlmProvider.OPENAI_COMPATIBLE.defaultEndpointUrl,
                model = preferences[Keys.llmModel].orEmpty(),
                apiKey = apiKeyStore.read(),
                anthropicVersion = preferences[Keys.llmAnthropicVersion] ?: "2023-06-01",
            ),
        )
    }

    val libraryItems: Flow<List<LibraryItem>> = combine(
        dao.observeFolders(),
        dao.observeBanks(),
    ) { folders, banks ->
        folders.map { folder ->
            LibraryItem(
                id = folder.id,
                name = folder.name,
                type = LibraryItemType.FOLDER,
                parentId = folder.parentId,
                createdAt = folder.createdAt,
            )
        } + banks.map { bank ->
            LibraryItem(
                id = bank.id,
                name = bank.name,
                type = LibraryItemType.QUESTION_BANK,
                parentId = bank.parentId,
                uri = bank.sourceUri,
                createdAt = bank.importedAt,
                importedAt = bank.importedAt,
                lastOpenedAt = bank.lastOpenedAt,
                questionCount = bank.questionCount,
            )
        }
    }

    val recentBanks: Flow<List<LibraryItem>> = libraryItems.map { items ->
        items.filter { it.type == LibraryItemType.QUESTION_BANK }
            .sortedByDescending(LibraryItem::activityAt)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setColorTheme(theme: ColorTheme) {
        dataStore.edit { it[Keys.colorTheme] = theme.name }
    }

    suspend fun setTextScale(scale: Float) {
        dataStore.edit { it[Keys.textScale] = scale.coerceIn(0.8f, 1.3f) }
    }

    suspend fun setSingleChoiceAutoNext(enabled: Boolean) {
        dataStore.edit { it[Keys.singleChoiceAutoNext] = enabled }
    }

    suspend fun setLlmConfig(config: LlmConfig) {
        require(config.endpointUrl.startsWith("https://") || config.endpointUrl.startsWith("http://")) {
            "API URL 必须以 http:// 或 https:// 开头"
        }
        require(config.model.isNotBlank()) { "请填写模型名称" }
        require(config.apiKey.isNotBlank()) { "请填写 API Key" }
        apiKeyStore.write(config.apiKey.trim())
        dataStore.edit { preferences ->
            preferences[Keys.llmProvider] = config.provider.name
            preferences[Keys.llmEndpointUrl] = config.endpointUrl.trim()
            preferences[Keys.llmModel] = config.model.trim()
            preferences[Keys.llmAnthropicVersion] = config.anthropicVersion.trim().ifBlank { "2023-06-01" }
        }
    }

    suspend fun addFolder(name: String, parentId: String?) {
        dao.insertFolder(
            FolderEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                parentId = parentId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun importQuestionBank(
        name: String,
        uri: String,
        parentId: String?,
        questions: List<ParsedQuestion>,
    ): String {
        require(questions.isNotEmpty()) { "没有可导入的题目" }
        val bankId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.insertBank(
                QuestionBankEntity(
                    id = bankId,
                    name = name.trim(),
                    parentId = parentId,
                    sourceUri = uri,
                    importedAt = now,
                    lastOpenedAt = null,
                    questionCount = questions.size,
                ),
            )
            questions.forEachIndexed { index, parsed ->
                val questionId = dao.insertQuestion(
                    QuestionEntity(
                        bankId = bankId,
                        position = index,
                        type = parsed.type.name,
                        prompt = parsed.prompt,
                        answer = parsed.answer,
                        explanation = parsed.explanation,
                        sheetName = parsed.sheetName,
                        sourceRow = parsed.sourceRow,
                    ),
                )
                dao.insertOptions(
                    parsed.options.mapIndexed { optionIndex, option ->
                        OptionEntity(
                            questionId = questionId,
                            label = option.label,
                            text = option.text,
                            position = optionIndex,
                            isCorrect = option.isCorrect,
                        )
                    },
                )
            }
        }
        return bankId
    }

    suspend fun renameItem(item: LibraryItem, name: String) {
        if (item.type == LibraryItemType.FOLDER) {
            dao.renameFolder(item.id, name.trim())
        } else {
            dao.renameBank(item.id, name.trim())
        }
    }

    suspend fun deleteItem(item: LibraryItem) {
        if (item.type == LibraryItemType.FOLDER) dao.deleteFolder(item.id) else dao.deleteBank(item.id)
    }

    suspend fun moveItem(item: LibraryItem, destinationFolderId: String?) {
        require(item.parentId != destinationFolderId) { "条目已经在这个位置" }
        if (item.type == LibraryItemType.FOLDER) {
            var cursor = destinationFolderId
            val visited = mutableSetOf<String>()
            while (cursor != null && visited.add(cursor)) {
                require(cursor != item.id) { "不能将文件夹移动到自身或其子文件夹中" }
                cursor = dao.getFolder(cursor)?.parentId
            }
            dao.moveFolder(item.id, destinationFolderId)
        } else {
            dao.moveBank(item.id, destinationFolderId)
        }
    }

    suspend fun openQuestionBank(id: String, pageSize: Int = 40): List<QuizQuestion> {
        dao.touchBank(id, System.currentTimeMillis())
        return loadQuestionPage(id, offset = 0, limit = pageSize)
    }

    suspend fun touchQuestionBank(id: String) {
        dao.touchBank(id, System.currentTimeMillis())
    }

    suspend fun loadQuestionPage(id: String, offset: Int, limit: Int = 40): List<QuizQuestion> {
        return dao.getQuestionsPage(id, offset.coerceAtLeast(0), limit.coerceIn(1, 100)).map { it.toQuizQuestion() }
    }

    fun observePracticeSessions(bankId: String): Flow<List<PracticeSession>> =
        dao.observePracticeSessions(bankId)
            .onStart { dao.reconcileCompletedSessions(bankId) }
            .map { rows -> rows.map { it.toModel() } }

    suspend fun getLatestUnfinishedPractice(bankId: String): PracticeSession? =
        dao.getLatestUnfinishedPractice(bankId)?.toModel()

    suspend fun getPracticeSession(sessionId: String): PracticeSession? =
        dao.getPracticeSession(sessionId)?.toModel()

    suspend fun preparePracticeSession(sessionId: String): PracticeSession? = database.withTransaction {
        val row = dao.getPracticeSession(sessionId) ?: return@withTransaction null
        val bank = dao.getBank(row.session.bankId) ?: return@withTransaction row.toModel()
        val settings = bank.toPracticeSettings()
        val mode = runCatching { PracticeMode.valueOf(row.session.mode) }.getOrDefault(PracticeMode.NORMAL)
        val desiredOrder = desiredOrderMode(mode, settings)
        if (row.session.orderMode != desiredOrder.name) {
            val existingIds = dao.getSessionQuestionIds(sessionId)
            val relations = dao.getQuestionsByIds(existingIds).associateBy { it.question.id }
            val reorderedIds = when (desiredOrder) {
                SessionOrderMode.TYPE_FIRST -> existingIds.sortedWith(
                    compareBy<Long> { relations[it]?.question?.type.typeOrder }
                        .thenBy { relations[it]?.question?.position ?: Int.MAX_VALUE },
                )
                SessionOrderMode.POSITION_FIRST -> existingIds.sortedBy {
                    relations[it]?.question?.position ?: Int.MAX_VALUE
                }
                SessionOrderMode.SHUFFLED -> existingIds.shuffled()
                SessionOrderMode.LEGACY -> existingIds
            }
            val answeredIds = dao.getAnsweredQuestionIds(sessionId).toSet()
            val nextUnanswered = reorderedIds.indexOfFirst { it !in answeredIds }
                .takeIf { it >= 0 }
                ?: (reorderedIds.lastIndex).coerceAtLeast(0)
            dao.deleteSessionQuestions(sessionId)
            if (reorderedIds.isNotEmpty()) {
                dao.insertSessionQuestions(
                    reorderedIds.mapIndexed { index, questionId ->
                        SessionQuestionEntity(sessionId, questionId, index)
                    },
                )
            }
            dao.updateSessionOrdering(
                sessionId = sessionId,
                currentPosition = if (row.session.completedAt == null) nextUnanswered else 0,
                totalQuestions = reorderedIds.size,
                orderMode = desiredOrder.name,
            )
        }
        dao.getPracticeSession(sessionId)?.toModel()
    }

    suspend fun createPracticeSession(
        bankId: String,
        mode: PracticeMode,
        minimumWrongCount: Int = 1,
    ): PracticeSession {
        val bankSettings = dao.getBank(bankId)?.toPracticeSettings() ?: BankPracticeSettings()
        val orderMode = desiredOrderMode(mode, bankSettings)
        val orderedQuestionIds = when (mode) {
            PracticeMode.NORMAL -> dao.getQuestionIds(bankId, bankSettings.typeFirstOrdering)
            PracticeMode.WRONG -> dao.getWrongQuestionIds(
                bankId,
                minimumWrongCount.coerceAtLeast(1),
                bankSettings.typeFirstOrdering,
            )
            PracticeMode.GUESSED -> dao.getGuessedQuestionIds(bankId)
        }
        val questionIds = if (orderMode == SessionOrderMode.SHUFFLED) {
            orderedQuestionIds.shuffled()
        } else {
            orderedQuestionIds
        }
        require(questionIds.isNotEmpty()) {
            when (mode) {
                PracticeMode.WRONG -> "当前等级下暂无错题"
                PracticeMode.GUESSED -> "暂无标记为蒙对的题目"
                PracticeMode.NORMAL -> "题库中暂无题目"
            }
        }
        val now = System.currentTimeMillis()
        val session = PracticeSessionEntity(
            id = UUID.randomUUID().toString(),
            bankId = bankId,
            mode = mode.name,
            startedAt = now,
            completedAt = null,
            currentPosition = 0,
            totalQuestions = questionIds.size,
            orderMode = orderMode.name,
        )
        database.withTransaction {
            dao.touchBank(bankId, now)
            dao.insertPracticeSession(session)
            dao.insertSessionQuestions(
                questionIds.mapIndexed { index, questionId ->
                    SessionQuestionEntity(session.id, questionId, index)
                },
            )
        }
        return PracticeSession(
            id = session.id,
            bankId = bankId,
            mode = mode,
            startedAt = now,
            completedAt = null,
            currentPosition = 0,
            totalQuestions = questionIds.size,
        )
    }

    suspend fun loadPracticeQuestionPage(
        sessionId: String,
        offset: Int,
        limit: Int = 40,
    ): List<QuizQuestion> = dao.getSessionQuestionsPage(
        sessionId,
        offset.coerceAtLeast(0),
        limit.coerceIn(1, 100),
    ).map { it.toQuizQuestion() }

    suspend fun getPracticeAnswers(sessionId: String): List<PracticeAnswerSnapshot> =
        dao.getPracticeAnswers(sessionId).map { row ->
            PracticeAnswerSnapshot(
                questionId = row.questionId,
                position = row.position,
                selectedLabels = row.selectedLabels.split(',').filter(String::isNotBlank).toSet(),
                isCorrect = row.isCorrect,
                answeredAt = row.answeredAt,
            )
        }

    suspend fun savePracticeAnswer(
        sessionId: String,
        questionId: Long,
        selectedLabels: Set<String>,
        isCorrect: Boolean,
    ) {
        database.withTransaction {
            dao.upsertPracticeAnswer(
                PracticeAnswerEntity(
                    sessionId = sessionId,
                    questionId = questionId,
                    selectedLabels = selectedLabels.sorted().joinToString(","),
                    isCorrect = isCorrect,
                    answeredAt = System.currentTimeMillis(),
                ),
            )
            dao.completePracticeIfAllAnswered(sessionId, System.currentTimeMillis())
        }
    }

    suspend fun updatePracticePosition(sessionId: String, position: Int) {
        dao.updatePracticePosition(sessionId, position.coerceAtLeast(0))
    }

    suspend fun completePracticeSession(sessionId: String) {
        dao.completePracticeSession(sessionId, System.currentTimeMillis())
    }

    suspend fun getWrongQuestionCount(bankId: String, minimumWrongCount: Int): Int =
        dao.getWrongQuestionCount(bankId, minimumWrongCount.coerceAtLeast(1))

    suspend fun getWrongQuestions(bankId: String, minimumWrongCount: Int = 1): List<QuizQuestion> {
        val settings = dao.getBank(bankId)?.toPracticeSettings() ?: BankPracticeSettings()
        val questionIds = dao.getWrongQuestionIds(
            bankId = bankId,
            minimumWrongCount = minimumWrongCount.coerceAtLeast(1),
            typeFirstOrdering = settings.typeFirstOrdering,
        )
        if (questionIds.isEmpty()) return emptyList()
        val relations = dao.getQuestionsByIds(questionIds).associateBy { it.question.id }
        return questionIds.mapIndexedNotNull { index, questionId ->
            relations[questionId]?.toQuizQuestion(position = index)
        }
    }

    suspend fun getMaximumWrongCount(bankId: String): Int = dao.getMaximumWrongCount(bankId)

    suspend fun getGuessedQuestionCount(bankId: String): Int = dao.getGuessedQuestionCount(bankId)

    suspend fun getGuessedQuestionIds(bankId: String): Set<Long> = dao.getGuessedQuestionIds(bankId).toSet()

    suspend fun getVisibleQuestionCount(bankId: String): Int = dao.getVisibleQuestionCount(bankId)

    suspend fun getHiddenQuestionCount(bankId: String): Int = dao.getHiddenQuestionCount(bankId)

    suspend fun getBankPracticeSettings(bankId: String): BankPracticeSettings =
        dao.getBank(bankId)?.toPracticeSettings() ?: BankPracticeSettings()

    suspend fun setShuffleReviewQuestions(bankId: String, enabled: Boolean) {
        dao.setShuffleReviewQuestions(bankId, enabled)
    }

    suspend fun setTypeFirstOrdering(bankId: String, enabled: Boolean) {
        dao.setTypeFirstOrdering(bankId, enabled)
    }

    suspend fun setQuestionHidden(questionId: Long, hidden: Boolean) {
        dao.setQuestionHidden(questionId, hidden)
    }

    suspend fun hideDuplicateQuestions(bankId: String): Int = database.withTransaction {
        val seen = mutableSetOf<String>()
        val duplicateIds = dao.getAllQuestions(bankId)
            .asSequence()
            .filterNot { it.question.hidden }
            .mapNotNull { relation -> relation.question.duplicateKey()?.let { key -> relation to key } }
            .filterNot { (_, key) -> seen.add(key) }
            .map { (relation, _) -> relation.question.id }
            .toList()
        if (duplicateIds.isNotEmpty()) dao.hideQuestions(duplicateIds)
        duplicateIds.size
    }

    suspend fun hideQuestionsDuplicatedInBanks(bankId: String, referenceBankIds: Set<String>): Int =
        database.withTransaction {
            val references = referenceBankIds - bankId
            if (references.isEmpty()) return@withTransaction 0
            val referenceKeys = dao.getQuestionsInBanks(references.toList())
                .mapNotNullTo(hashSetOf()) { it.question.duplicateKey() }
            val duplicateIds = dao.getAllQuestions(bankId)
                .asSequence()
                .filterNot { it.question.hidden }
                .filter { it.question.duplicateKey() in referenceKeys }
                .map { it.question.id }
                .toList()
            if (duplicateIds.isNotEmpty()) dao.hideQuestions(duplicateIds)
            duplicateIds.size
        }

    suspend fun mergeQuestionBank(targetBankId: String, sourceBankId: String): QuestionBankMergeResult =
        combineQuestionBanks(targetBankId, sourceBankId, includeMissingQuestions = true)

    suspend fun crossQuestionBank(targetBankId: String, sourceBankId: String): QuestionBankMergeResult =
        combineQuestionBanks(targetBankId, sourceBankId, includeMissingQuestions = false)

    private suspend fun combineQuestionBanks(
        targetBankId: String,
        sourceBankId: String,
        includeMissingQuestions: Boolean,
    ): QuestionBankMergeResult = database.withTransaction {
        require(targetBankId != sourceBankId) { "不能选择当前题库" }
        requireNotNull(dao.getBank(targetBankId)) { "当前题库不存在" }
        requireNotNull(dao.getBank(sourceBankId)) { "来源题库不存在" }

        val targetQuestions = dao.getAllQuestions(targetBankId)
        val targetQuestionIdsByKey = mutableMapOf<String, Long>()
        targetQuestions
            .sortedWith(
                compareBy<QuestionWithOptions> { it.question.hidden }
                    .thenBy { it.question.position },
            )
            .forEach { relation ->
                relation.question.duplicateKey()?.let { key ->
                    if (key !in targetQuestionIdsByKey) {
                        targetQuestionIdsByKey[key] = relation.question.id
                    }
                }
            }
        val sourceQuestions = dao.getAllQuestions(sourceBankId)
        val targetQuestionIdBySourceId = mutableMapOf<Long, Long>()
        val overlappingTargetQuestionIds = mutableSetOf<Long>()
        var nextPosition = (targetQuestions.maxOfOrNull { it.question.position } ?: -1) + 1
        var addedQuestions = 0

        sourceQuestions.forEach { relation ->
            val duplicateKey = relation.question.duplicateKey()
            val existingTargetQuestionId = duplicateKey?.let(targetQuestionIdsByKey::get)
            if (existingTargetQuestionId != null) {
                targetQuestionIdBySourceId[relation.question.id] = existingTargetQuestionId
                overlappingTargetQuestionIds += existingTargetQuestionId
                return@forEach
            }
            if (!includeMissingQuestions || relation.question.hidden) return@forEach

            val questionId = dao.insertQuestion(
                relation.question.copy(
                    id = 0,
                    bankId = targetBankId,
                    position = nextPosition++,
                    hidden = false,
                ),
            )
            val options = relation.options
                .sortedBy { it.position }
                .map { option -> option.copy(id = 0, questionId = questionId) }
            if (options.isNotEmpty()) dao.insertOptions(options)
            targetQuestionIdBySourceId[relation.question.id] = questionId
            duplicateKey?.let { targetQuestionIdsByKey[it] = questionId }
            addedQuestions++
        }

        val targetSessionsByOrigin = dao.getPracticeSessionEntities(targetBankId)
            .associateByTo(mutableMapOf()) { it.originSessionId ?: it.id }
        val targetSessionQuestions = dao.getSessionQuestionEntities(targetBankId)
            .groupBy(SessionQuestionEntity::sessionId)
            .mapValuesTo(mutableMapOf()) { (_, rows) -> rows.toMutableList() }
        val targetAnswers = dao.getPracticeAnswerEntities(targetBankId)
            .groupBy(PracticeAnswerEntity::sessionId)
            .mapValuesTo(mutableMapOf()) { (_, answers) -> answers.toMutableList() }
        val sourceSessions = dao.getPracticeSessionEntities(sourceBankId)
        val sourceSessionQuestions = dao.getSessionQuestionEntities(sourceBankId)
            .groupBy(SessionQuestionEntity::sessionId)
        val sourceAnswers = dao.getPracticeAnswerEntities(sourceBankId)
            .groupBy(PracticeAnswerEntity::sessionId)
        val targetSessionIdBySourceSessionId = mutableMapOf<String, String>()
        var mergedSessions = 0
        var mergedAnswers = 0
        var mergedWrongAnswers = 0

        sourceSessions.forEach { sourceSession ->
            val sourceQuestionRows = sourceSessionQuestions[sourceSession.id].orEmpty()
            val seenMappedQuestionIds = mutableSetOf<Long>()
            val mappedQuestionIds = sourceQuestionRows.mapNotNull { sourceRow ->
                val targetQuestionId = targetQuestionIdBySourceId[sourceRow.questionId]
                    ?: return@mapNotNull null
                if (!seenMappedQuestionIds.add(targetQuestionId)) return@mapNotNull null
                targetQuestionId
            }
            if (mappedQuestionIds.isEmpty()) return@forEach

            val originSessionId = sourceSession.originSessionId ?: sourceSession.id
            val existingTargetSession = targetSessionsByOrigin[originSessionId]
            val targetSessionId = existingTargetSession?.id
                ?: mergedPracticeSessionId(targetBankId, originSessionId)
            val existingQuestionRows = targetSessionQuestions[targetSessionId].orEmpty()
            val existingQuestionIds = existingQuestionRows.mapTo(mutableSetOf(), SessionQuestionEntity::questionId)
            val newQuestionIds = mappedQuestionIds.filterNot(existingQuestionIds::contains)
            var sessionMetadataChanged = false

            var targetSession = existingTargetSession
            if (targetSession == null) {
                val sourceCurrentQuestionId = sourceQuestionRows
                    .firstOrNull { it.orderIndex == sourceSession.currentPosition }
                    ?.questionId
                val targetCurrentQuestionId = sourceCurrentQuestionId?.let(targetQuestionIdBySourceId::get)
                val targetCurrentPosition = targetCurrentQuestionId
                    ?.let(mappedQuestionIds::indexOf)
                    ?.takeIf { it >= 0 }
                    ?: sourceSession.currentPosition.coerceIn(0, mappedQuestionIds.lastIndex)
                targetSession = sourceSession.copy(
                    id = targetSessionId,
                    bankId = targetBankId,
                    currentPosition = targetCurrentPosition,
                    totalQuestions = mappedQuestionIds.size,
                    originSessionId = originSessionId,
                )
                dao.insertPracticeSession(targetSession)
                val insertedRows = mappedQuestionIds.mapIndexed { index, questionId ->
                    SessionQuestionEntity(targetSessionId, questionId, index)
                }
                dao.insertSessionQuestions(insertedRows)
                targetSessionQuestions[targetSessionId] = insertedRows.toMutableList()
            } else if (newQuestionIds.isNotEmpty()) {
                val nextOrderIndex = (existingQuestionRows.maxOfOrNull { it.orderIndex } ?: -1) + 1
                val insertedRows = newQuestionIds.mapIndexed { index, questionId ->
                    SessionQuestionEntity(targetSessionId, questionId, nextOrderIndex + index)
                }
                dao.insertSessionQuestions(insertedRows)
                val totalQuestions = existingQuestionRows.size + insertedRows.size
                val completedAt = targetSession.completedAt ?: sourceSession.completedAt
                val currentPosition = if (completedAt != null) {
                    totalQuestions - 1
                } else {
                    targetSession.currentPosition.coerceIn(0, totalQuestions - 1)
                }
                dao.updateMergedPracticeSession(
                    sessionId = targetSessionId,
                    currentPosition = currentPosition,
                    totalQuestions = totalQuestions,
                    completedAt = completedAt,
                )
                targetSession = targetSession.copy(
                    currentPosition = currentPosition,
                    totalQuestions = totalQuestions,
                    completedAt = completedAt,
                )
                targetSessionQuestions.getOrPut(targetSessionId, ::mutableListOf).addAll(insertedRows)
                sessionMetadataChanged = true
            }

            val existingAnswerQuestionIds = targetAnswers[targetSessionId]
                .orEmpty()
                .mapTo(mutableSetOf(), PracticeAnswerEntity::questionId)
            val mappedAnswers = sourceAnswers[sourceSession.id]
                .orEmpty()
                .mapNotNull { sourceAnswer ->
                    targetQuestionIdBySourceId[sourceAnswer.questionId]?.let { targetQuestionId ->
                        targetQuestionId to sourceAnswer
                    }
                }
                .groupBy { it.first }
                .map { (targetQuestionId, answers) ->
                    answers.maxBy { it.second.answeredAt }.second.copy(
                        sessionId = targetSessionId,
                        questionId = targetQuestionId,
                    )
                }
                .filterNot { it.questionId in existingAnswerQuestionIds }
            if (mappedAnswers.isNotEmpty()) {
                dao.insertPracticeAnswers(mappedAnswers)
                targetAnswers.getOrPut(targetSessionId, ::mutableListOf).addAll(mappedAnswers)
            }

            if (existingTargetSession != null && targetSession?.completedAt == null && sourceSession.completedAt != null) {
                val completedPosition = targetSession.totalQuestions - 1
                dao.updateMergedPracticeSession(
                    sessionId = targetSessionId,
                    currentPosition = completedPosition,
                    totalQuestions = targetSession.totalQuestions,
                    completedAt = sourceSession.completedAt,
                )
                targetSession = targetSession.copy(
                    currentPosition = completedPosition,
                    completedAt = sourceSession.completedAt,
                )
                sessionMetadataChanged = true
            }

            if (existingTargetSession == null || newQuestionIds.isNotEmpty() || mappedAnswers.isNotEmpty() || sessionMetadataChanged) {
                mergedSessions++
            }
            mergedAnswers += mappedAnswers.size
            mergedWrongAnswers += mappedAnswers.count { !it.isCorrect }
            targetSessionIdBySourceSessionId[sourceSession.id] = targetSessionId
            targetSessionsByOrigin[originSessionId] = requireNotNull(targetSession)
        }

        val targetGuessedQuestionIds = dao.getGuessedQuestionEntities(targetBankId)
            .mapTo(mutableSetOf(), GuessedQuestionEntity::questionId)
        var mergedGuessedQuestions = 0
        dao.getGuessedQuestionEntities(sourceBankId).forEach { sourceGuessed ->
            val targetQuestionId = targetQuestionIdBySourceId[sourceGuessed.questionId]
                ?: return@forEach
            val targetSessionId = targetSessionIdBySourceSessionId[sourceGuessed.sessionId]
                ?: return@forEach
            if (!targetGuessedQuestionIds.add(targetQuestionId)) return@forEach
            dao.upsertGuessedQuestion(
                sourceGuessed.copy(
                    questionId = targetQuestionId,
                    sessionId = targetSessionId,
                ),
            )
            mergedGuessedQuestions++
        }

        val result = QuestionBankMergeResult(
            overlappingQuestions = overlappingTargetQuestionIds.size,
            addedQuestions = addedQuestions,
            mergedSessions = mergedSessions,
            mergedAnswers = mergedAnswers,
            mergedWrongAnswers = mergedWrongAnswers,
            mergedGuessedQuestions = mergedGuessedQuestions,
        )
        if (addedQuestions > 0) {
            dao.refreshBankQuestionCount(targetBankId, System.currentTimeMillis())
        } else if (result.changed) {
            dao.touchBank(targetBankId, System.currentTimeMillis())
        }
        result
    }

    suspend fun setQuestionGuessed(sessionId: String, questionId: Long, guessed: Boolean) {
        if (guessed) {
            dao.upsertGuessedQuestion(
                GuessedQuestionEntity(
                    questionId = questionId,
                    sessionId = sessionId,
                    markedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            dao.deleteGuessedQuestion(questionId)
        }
    }

    suspend fun deletePracticeSessions(sessionIds: Set<String>) {
        if (sessionIds.isNotEmpty()) dao.deletePracticeSessions(sessionIds.toList())
    }

    suspend fun getAllQuestions(bankId: String): List<QuizQuestion> =
        dao.getAllQuestions(bankId).map { it.toQuizQuestion() }

    suspend fun createRandomQuestionSet(
        bankId: String,
        types: Set<QuestionType>,
        count: Int,
    ): List<QuizQuestion> {
        require(types.isNotEmpty()) { "请至少选择一种题型" }
        val ids = dao.getRandomQuestionIds(bankId, types.map(QuestionType::name), count.coerceIn(1, 200))
        require(ids.isNotEmpty()) { "没有符合条件的题目" }
        val relations = dao.getQuestionsByIds(ids).associateBy { it.question.id }
        return ids.mapIndexedNotNull { index, id ->
            relations[id]?.toQuizQuestion(position = index)
        }
    }

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val colorTheme = stringPreferencesKey("color_theme")
        val textScale = floatPreferencesKey("text_scale")
        val singleChoiceAutoNext = booleanPreferencesKey("single_choice_auto_next")
        val llmProvider = stringPreferencesKey("llm_provider")
        val llmEndpointUrl = stringPreferencesKey("llm_endpoint_url")
        val llmModel = stringPreferencesKey("llm_model")
        val llmAnthropicVersion = stringPreferencesKey("llm_anthropic_version")
    }
}

private fun QuestionWithOptions.toQuizQuestion(position: Int = question.position): QuizQuestion = QuizQuestion(
    id = question.id,
    position = position,
    type = runCatching { QuestionType.valueOf(question.type) }.getOrDefault(QuestionType.UNKNOWN),
    prompt = question.prompt,
    options = options.sortedBy { it.position }.map { option ->
        QuestionOption(option.label, option.text, option.isCorrect)
    },
    answer = question.answer,
    explanation = question.explanation,
    sheetName = question.sheetName,
    hidden = question.hidden,
)

private fun SessionQuestionWithOptions.toQuizQuestion(): QuizQuestion =
    QuestionWithOptions(question, options).toQuizQuestion(practicePosition)

private fun QuestionBankEntity.toPracticeSettings(): BankPracticeSettings = BankPracticeSettings(
    shuffleReviewQuestions = shuffleReviewQuestions,
    typeFirstOrdering = typeFirstOrdering,
)

private fun desiredOrderMode(mode: PracticeMode, settings: BankPracticeSettings): SessionOrderMode = when (mode) {
    PracticeMode.NORMAL -> if (settings.typeFirstOrdering) SessionOrderMode.TYPE_FIRST else SessionOrderMode.POSITION_FIRST
    PracticeMode.WRONG -> when {
        settings.shuffleReviewQuestions -> SessionOrderMode.SHUFFLED
        settings.typeFirstOrdering -> SessionOrderMode.TYPE_FIRST
        else -> SessionOrderMode.POSITION_FIRST
    }
    PracticeMode.GUESSED -> if (settings.shuffleReviewQuestions) SessionOrderMode.SHUFFLED else SessionOrderMode.POSITION_FIRST
}

private enum class SessionOrderMode {
    LEGACY,
    TYPE_FIRST,
    POSITION_FIRST,
    SHUFFLED,
}

private val String?.typeOrder: Int
    get() = when (this) {
        QuestionType.SINGLE_CHOICE.name -> 0
        QuestionType.MULTIPLE_CHOICE.name -> 1
        QuestionType.TRUE_FALSE.name -> 2
        QuestionType.CALCULATION.name -> 3
        QuestionType.SHORT_ANSWER.name -> 4
        else -> 5
    }

private fun com.hagemon.squiz.data.local.PracticeSessionSummaryRow.toModel(): PracticeSession = PracticeSession(
    id = session.id,
    bankId = session.bankId,
    mode = runCatching { PracticeMode.valueOf(session.mode) }.getOrDefault(PracticeMode.NORMAL),
    startedAt = session.startedAt,
    completedAt = session.completedAt,
    currentPosition = session.currentPosition,
    totalQuestions = session.totalQuestions,
    answeredCount = answeredCount,
    correctCount = correctCount,
)

private val androidx.datastore.core.DataStore<Preferences>.safeData: Flow<Preferences>
    get() = data.catch { exception ->
        if (exception is IOException) emit(emptyPreferences()) else throw exception
    }

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

private fun mergedPracticeSessionId(targetBankId: String, originSessionId: String): String =
    UUID.nameUUIDFromBytes("squiz-merge:$targetBankId:$originSessionId".toByteArray()).toString()

private fun QuestionEntity.duplicateKey(): String? {
    val normalizedPrompt = prompt.normalizedForDuplicate()
    val normalizedAnswer = answer.normalizedForDuplicate().let { value ->
        if (type == QuestionType.SINGLE_CHOICE.name || type == QuestionType.MULTIPLE_CHOICE.name) {
            value.replace(Regex("[\\s,，;；、/|]+"), "")
        } else {
            value
        }
    }
    if (normalizedPrompt.isBlank() || normalizedAnswer.isBlank()) return null
    return "$normalizedPrompt\u0000$normalizedAnswer"
}

private fun String.normalizedForDuplicate(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
