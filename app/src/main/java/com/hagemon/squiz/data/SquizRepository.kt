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
import com.hagemon.squiz.model.AppSettings
import com.hagemon.squiz.model.ColorTheme
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.LibraryItemType
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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private val Context.squizDataStore by preferencesDataStore(name = "squiz_preferences")

class SquizRepository(context: Context) {
    private val dataStore = context.applicationContext.squizDataStore
    private val database = SquizDatabase.get(context)
    private val dao = database.dao()

    val settings: Flow<AppSettings> = dataStore.safeData.map { preferences ->
        AppSettings(
            themeMode = preferences[Keys.themeMode].toEnumOrDefault(ThemeMode.SYSTEM),
            colorTheme = preferences[Keys.colorTheme].toEnumOrDefault(ColorTheme.LAVENDER),
            textScale = (preferences[Keys.textScale] ?: 1f).coerceIn(0.8f, 1.3f),
            singleChoiceAutoNext = preferences[Keys.singleChoiceAutoNext] ?: false,
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

    suspend fun createPracticeSession(
        bankId: String,
        mode: PracticeMode,
        minimumWrongCount: Int = 1,
    ): PracticeSession {
        val questionIds = when (mode) {
            PracticeMode.NORMAL -> dao.getQuestionIds(bankId)
            PracticeMode.WRONG -> dao.getWrongQuestionIds(bankId, minimumWrongCount.coerceAtLeast(1))
            PracticeMode.GUESSED -> dao.getGuessedQuestionIds(bankId)
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

    suspend fun getMaximumWrongCount(bankId: String): Int = dao.getMaximumWrongCount(bankId)

    suspend fun getGuessedQuestionCount(bankId: String): Int = dao.getGuessedQuestionCount(bankId)

    suspend fun getGuessedQuestionIds(bankId: String): Set<Long> = dao.getGuessedQuestionIds(bankId).toSet()

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
)

private fun SessionQuestionWithOptions.toQuizQuestion(): QuizQuestion =
    QuestionWithOptions(question, options).toQuizQuestion(practicePosition)

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
