package com.hagemon.squiz.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.hagemon.squiz.data.SquizRepository
import com.hagemon.squiz.data.excel.ExcelAnalyzer
import com.hagemon.squiz.data.excel.ExcelWorkbookReader
import com.hagemon.squiz.model.AppSettings
import com.hagemon.squiz.model.ImportAnalysis
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.ParsedQuestion
import com.hagemon.squiz.model.PracticeAnswerSnapshot
import com.hagemon.squiz.model.PracticeMode
import com.hagemon.squiz.model.PracticeSession
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.QuizQuestion
import com.hagemon.squiz.model.SheetMapping
import com.hagemon.squiz.ui.screens.ImportConfigScreen
import com.hagemon.squiz.ui.screens.ImportMappingScreen
import com.hagemon.squiz.ui.screens.ImportPreviewScreen
import com.hagemon.squiz.ui.screens.LibraryScreen
import com.hagemon.squiz.ui.screens.MoveItemScreen
import com.hagemon.squiz.ui.screens.PracticeHistoryScreen
import com.hagemon.squiz.ui.screens.QuestionBankScreen
import com.hagemon.squiz.ui.screens.QuizScreen
import com.hagemon.squiz.ui.screens.QuizSettingsScreen
import com.hagemon.squiz.ui.screens.SettingsScreen
import com.hagemon.squiz.ui.screens.ThemeSettingsScreen
import kotlinx.coroutines.launch

private enum class Destination {
    LIBRARY,
    SETTINGS,
    THEME,
    QUIZ_SETTINGS,
    IMPORT_CONFIG,
    IMPORT_MAPPING,
    IMPORT_PREVIEW,
    MOVE_ITEM,
    QUESTION_BANK,
    PRACTICE_HISTORY,
    QUIZ,
}

private data class PendingFile(
    val uri: Uri,
    val sourceFileName: String,
    val defaultParentId: String?,
)

private data class PendingImport(
    val uri: Uri,
    val sourceFileName: String,
    val bankName: String,
    val parentId: String?,
    val analysis: ImportAnalysis,
    val mappings: List<SheetMapping>,
)

@Composable
fun SquizApp(
    repository: SquizRepository,
    settings: AppSettings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(Destination.LIBRARY) }
    var pendingFile by remember { mutableStateOf<PendingFile?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var parsedQuestions by remember { mutableStateOf<List<ParsedQuestion>>(emptyList()) }
    var quizQuestions by remember { mutableStateOf<List<QuizQuestion>>(emptyList()) }
    var quizBankId by remember { mutableStateOf("") }
    var quizBankName by remember { mutableStateOf("") }
    var quizQuestionCount by remember { mutableIntStateOf(0) }
    var quizSessionId by remember { mutableStateOf<String?>(null) }
    var quizInitialPage by remember { mutableIntStateOf(0) }
    var quizInitialAnswers by remember { mutableStateOf<List<PracticeAnswerSnapshot>>(emptyList()) }
    var quizGuessedQuestionIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var quizReviewMode by remember { mutableStateOf(false) }
    var randomQuizQuestions by remember { mutableStateOf<List<QuizQuestion>>(emptyList()) }
    var selectedBank by remember { mutableStateOf<LibraryItem?>(null) }
    var moveTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var quizReturnDestination by remember { mutableStateOf(Destination.QUESTION_BANK) }
    var speedDialScrim by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val showNavigationBar = destination == Destination.LIBRARY || destination == Destination.SETTINGS

    fun message(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun leaveImport() {
        pendingFile = null
        pendingImport = null
        parsedQuestions = emptyList()
        destination = Destination.LIBRARY
    }

    fun leaveQuiz() {
        quizQuestions = emptyList()
        quizBankId = ""
        quizQuestionCount = 0
        quizSessionId = null
        quizInitialPage = 0
        quizInitialAnswers = emptyList()
        quizGuessedQuestionIds = emptySet()
        quizReviewMode = false
        randomQuizQuestions = emptyList()
        destination = quizReturnDestination
    }

    fun openSession(session: PracticeSession, reviewMode: Boolean, returnTo: Destination) {
        val bank = selectedBank ?: return
        scope.launch {
            busy = true
            runCatching {
                val currentSession = repository.getPracticeSession(session.id) ?: session
                val effectiveReviewMode = reviewMode || currentSession.isCompleted
                val initialPage = if (effectiveReviewMode) 0 else currentSession.currentPosition
                val chunkStart = initialPage / 40 * 40
                val questions = repository.loadPracticeQuestionPage(session.id, chunkStart, 40)
                val answers = repository.getPracticeAnswers(session.id)
                val guessedIds = repository.getGuessedQuestionIds(bank.id)
                quizBankId = bank.id
                quizBankName = bank.name
                quizQuestionCount = currentSession.totalQuestions
                quizSessionId = session.id
                quizInitialPage = initialPage
                quizInitialAnswers = answers
                quizGuessedQuestionIds = guessedIds
                quizReviewMode = effectiveReviewMode
                randomQuizQuestions = emptyList()
                quizQuestions = questions
                quizReturnDestination = returnTo
                destination = Destination.QUIZ
            }.onFailure { message(it.message ?: "无法打开练习") }
            busy = false
        }
    }

    fun createSession(mode: PracticeMode, minimumWrongCount: Int = 1) {
        val bank = selectedBank ?: return
        scope.launch {
            busy = true
            runCatching { repository.createPracticeSession(bank.id, mode, minimumWrongCount) }
                .onSuccess { session -> openSession(session, reviewMode = false, returnTo = Destination.QUESTION_BANK) }
                .onFailure { message(it.message ?: "无法开始练习") }
            busy = false
        }
    }

    fun openRandomPractice(types: Set<QuestionType>, count: Int) {
        val bank = selectedBank ?: return
        scope.launch {
            busy = true
            runCatching { repository.createRandomQuestionSet(bank.id, types, count) }
                .onSuccess { questions ->
                    quizBankId = bank.id
                    quizBankName = "${bank.name} · 随机练习"
                    quizQuestionCount = questions.size
                    quizSessionId = null
                    quizInitialPage = 0
                    quizInitialAnswers = emptyList()
                    quizGuessedQuestionIds = repository.getGuessedQuestionIds(bank.id)
                    quizReviewMode = false
                    randomQuizQuestions = questions
                    quizQuestions = questions.take(40)
                    quizReturnDestination = Destination.QUESTION_BANK
                    destination = Destination.QUIZ
                }
                .onFailure { message(it.message ?: "无法开始随机练习") }
            busy = false
        }
    }

    fun analyzeAndImport(file: PendingFile, bankName: String, parentId: String?) {
        scope.launch {
            busy = true
            runCatching {
                val workbook = ExcelWorkbookReader.read(context.contentResolver, file.uri, file.sourceFileName)
                val analysis = ExcelAnalyzer.analyze(workbook)
                if (analysis.requiresManualMapping) {
                    pendingImport = PendingImport(
                        uri = file.uri,
                        sourceFileName = file.sourceFileName,
                        bankName = bankName,
                        parentId = parentId,
                        analysis = analysis,
                        mappings = analysis.mappings,
                    )
                    destination = Destination.IMPORT_MAPPING
                } else {
                    val questions = ExcelAnalyzer.parse(workbook, analysis.mappings)
                    repository.importQuestionBank(bankName, file.uri.toString(), parentId, questions)
                    message("已导入 ${questions.size} 题")
                    leaveImport()
                }
            }.onFailure { message(it.message ?: "Excel 解析失败") }
            busy = false
        }
    }

    BackHandler(enabled = !showNavigationBar) {
        when (destination) {
            Destination.THEME, Destination.QUIZ_SETTINGS -> destination = Destination.SETTINGS
            Destination.IMPORT_PREVIEW -> destination = Destination.IMPORT_MAPPING
            Destination.IMPORT_CONFIG, Destination.IMPORT_MAPPING -> leaveImport()
            Destination.MOVE_ITEM -> {
                moveTarget = null
                destination = Destination.LIBRARY
            }
            Destination.QUESTION_BANK -> destination = Destination.LIBRARY
            Destination.PRACTICE_HISTORY -> destination = Destination.QUESTION_BANK
            Destination.QUIZ -> leaveQuiz()
            else -> destination = Destination.LIBRARY
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showNavigationBar) {
                    Box {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            NavigationBarItem(
                                selected = destination == Destination.LIBRARY,
                                onClick = { destination = Destination.LIBRARY },
                                icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                label = { Text("题库") },
                            )
                            NavigationBarItem(
                                selected = destination == Destination.SETTINGS,
                                onClick = { destination = Destination.SETTINGS },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                label = { Text("设置") },
                            )
                        }
                        if (speedDialScrim && destination == Destination.LIBRARY) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {},
                                    ),
                            )
                        }
                    }
                }
            },
        ) { contentPadding ->
            AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) {
                        AnimatedContentTransitionScope.SlideDirection.Left
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.Right
                    }
                    (fadeIn(tween(180)) + slideIntoContainer(direction, tween(280))) togetherWith
                        (fadeOut(tween(120)) + slideOutOfContainer(direction, tween(220)))
                },
                label = "主页面切换",
            ) { screen ->
                when (screen) {
                    Destination.LIBRARY -> LibraryScreen(
                        repository = repository,
                        outerPadding = contentPadding,
                        onImportFile = { uri, name, parentId ->
                            pendingFile = PendingFile(uri, name, parentId)
                            destination = Destination.IMPORT_CONFIG
                        },
                        onOpenBank = { bank: LibraryItem ->
                            selectedBank = bank
                            destination = Destination.QUESTION_BANK
                            scope.launch { repository.touchQuestionBank(bank.id) }
                        },
                        onMoveItem = { item ->
                            moveTarget = item
                            destination = Destination.MOVE_ITEM
                        },
                        onSpeedDialVisibilityChange = { speedDialScrim = it },
                    )
                    Destination.SETTINGS -> SettingsScreen(
                        outerPadding = contentPadding,
                        onThemeClick = { destination = Destination.THEME },
                        onQuizSettingsClick = { destination = Destination.QUIZ_SETTINGS },
                    )
                    Destination.THEME -> ThemeSettingsScreen(
                        settings = settings,
                        repository = repository,
                        onBack = { destination = Destination.SETTINGS },
                    )
                    Destination.QUIZ_SETTINGS -> QuizSettingsScreen(
                        settings = settings,
                        repository = repository,
                        onBack = { destination = Destination.SETTINGS },
                    )
                    Destination.IMPORT_CONFIG -> pendingFile?.let { file ->
                        ImportConfigScreen(
                            repository = repository,
                            sourceFileName = file.sourceFileName,
                            initialBankName = file.sourceFileName.substringBeforeLast('.', file.sourceFileName),
                            initialFolderId = file.defaultParentId,
                            onBack = ::leaveImport,
                            onConfirm = { bankName, parentId -> analyzeAndImport(file, bankName, parentId) },
                        )
                    }
                    Destination.IMPORT_MAPPING -> pendingImport?.let { pending ->
                        ImportMappingScreen(
                            analysis = pending.analysis,
                            mappings = pending.mappings,
                            onMappingsChange = { pendingImport = pending.copy(mappings = it) },
                            onBack = ::leaveImport,
                            onPreview = {
                                val questions = ExcelAnalyzer.parse(pending.analysis.workbook, pending.mappings)
                                if (questions.isEmpty()) {
                                    message("没有解析到题目，请检查字段对应关系")
                                } else {
                                    parsedQuestions = questions
                                    destination = Destination.IMPORT_PREVIEW
                                }
                            },
                        )
                    }
                    Destination.IMPORT_PREVIEW -> pendingImport?.let { pending ->
                        ImportPreviewScreen(
                            questions = parsedQuestions,
                            onBack = { destination = Destination.IMPORT_MAPPING },
                            onConfirm = {
                                scope.launch {
                                    busy = true
                                    runCatching {
                                        repository.importQuestionBank(
                                            pending.bankName,
                                            pending.uri.toString(),
                                            pending.parentId,
                                            parsedQuestions,
                                        )
                                    }.onSuccess {
                                        message("已导入 ${parsedQuestions.size} 题")
                                        leaveImport()
                                    }.onFailure { message(it.message ?: "导入失败") }
                                    busy = false
                                }
                            },
                        )
                    }
                    Destination.MOVE_ITEM -> moveTarget?.let { item ->
                        MoveItemScreen(
                            item = item,
                            repository = repository,
                            onBack = {
                                moveTarget = null
                                destination = Destination.LIBRARY
                            },
                            onMoved = {
                                message("已移动 ${item.name}")
                                moveTarget = null
                                destination = Destination.LIBRARY
                            },
                            onError = ::message,
                        )
                    }
                    Destination.QUESTION_BANK -> selectedBank?.let { bank ->
                        QuestionBankScreen(
                            bank = bank,
                            repository = repository,
                            onBack = { destination = Destination.LIBRARY },
                            onStartNew = { createSession(PracticeMode.NORMAL) },
                            onContinue = { session -> openSession(session, reviewMode = false, returnTo = Destination.QUESTION_BANK) },
                            onStartWrong = { level -> createSession(PracticeMode.WRONG, level) },
                            onStartGuessed = { createSession(PracticeMode.GUESSED) },
                            onOpenHistory = { destination = Destination.PRACTICE_HISTORY },
                            onStartRandom = ::openRandomPractice,
                        )
                    }
                    Destination.PRACTICE_HISTORY -> selectedBank?.let { bank ->
                        PracticeHistoryScreen(
                            bank = bank,
                            repository = repository,
                            onBack = { destination = Destination.QUESTION_BANK },
                            onOpenSession = { session ->
                                openSession(
                                    session,
                                    reviewMode = session.isCompleted,
                                    returnTo = Destination.PRACTICE_HISTORY,
                                )
                            },
                        )
                    }
                    Destination.QUIZ -> QuizScreen(
                        bankName = quizBankName,
                        initialQuestions = quizQuestions,
                        totalQuestionCount = quizQuestionCount,
                        singleChoiceAutoNext = settings.singleChoiceAutoNext,
                        initialPage = quizInitialPage,
                        initialAnswers = quizInitialAnswers,
                        initialGuessedQuestionIds = quizGuessedQuestionIds,
                        guessedMarkEnabled = quizSessionId != null,
                        reviewMode = quizReviewMode,
                        onLoadPage = { offset, limit ->
                            val sessionId = quizSessionId
                            if (sessionId != null) repository.loadPracticeQuestionPage(sessionId, offset, limit)
                            else randomQuizQuestions.drop(offset).take(limit)
                        },
                        onAnswer = { question, _, labels, isCorrect ->
                            val sessionId = quizSessionId
                            if (sessionId != null && !quizReviewMode) {
                                scope.launch {
                                    repository.savePracticeAnswer(sessionId, question.id, labels, isCorrect)
                                }
                            }
                        },
                        onPageChanged = { page ->
                            val sessionId = quizSessionId
                            if (sessionId != null && !quizReviewMode) {
                                scope.launch { repository.updatePracticePosition(sessionId, page) }
                            }
                        },
                        onGuessChanged = { questionId, guessed ->
                            val sessionId = quizSessionId
                            if (sessionId != null) {
                                scope.launch { repository.setQuestionGuessed(sessionId, questionId, guessed) }
                            }
                        },
                        onFinish = {
                            leaveQuiz()
                        },
                        onBack = ::leaveQuiz,
                    )
                }
            }
        }
        if (busy) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
