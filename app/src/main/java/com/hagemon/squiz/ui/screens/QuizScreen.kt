package com.hagemon.squiz.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hagemon.squiz.model.PracticeAnswerSnapshot
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.QuizQuestion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val questionPageSize = 40

private data class ChoiceAnswer(
    val selectedLabels: Set<String> = emptySet(),
    val submitted: Boolean = false,
)

private enum class QuestionAnswerStatus {
    CORRECT,
    INCORRECT,
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    bankName: String,
    initialQuestions: List<QuizQuestion>,
    totalQuestionCount: Int,
    singleChoiceAutoNext: Boolean,
    initialPage: Int = 0,
    initialAnswers: List<PracticeAnswerSnapshot> = emptyList(),
    initialGuessedQuestionIds: Set<Long> = emptySet(),
    reviewMode: Boolean = false,
    onLoadPage: suspend (offset: Int, limit: Int) -> List<QuizQuestion>,
    onAnswer: (question: QuizQuestion, page: Int, labels: Set<String>, isCorrect: Boolean) -> Unit = { _, _, _, _ -> },
    onPageChanged: (page: Int) -> Unit = {},
    onGuessChanged: (questionId: Long, guessed: Boolean) -> Unit = { _, _ -> },
    llmConfigured: Boolean = false,
    onAskLlm: suspend (question: QuizQuestion) -> String = { error("请先配置大模型接口") },
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val loadedQuestions = remember(initialQuestions) {
        mutableStateMapOf<Int, QuizQuestion>().apply {
            initialQuestions.forEach { question -> put(question.position, question) }
        }
    }
    val answerStates = remember(initialAnswers) {
        mutableStateMapOf<Long, ChoiceAnswer>().apply {
            initialAnswers.forEach { answer ->
                put(answer.questionId, ChoiceAnswer(answer.selectedLabels, submitted = true))
            }
        }
    }
    val statusByPosition = remember(initialAnswers) {
        mutableStateMapOf<Int, QuestionAnswerStatus>().apply {
            initialAnswers.forEach { answer ->
                put(answer.position, if (answer.isCorrect) QuestionAnswerStatus.CORRECT else QuestionAnswerStatus.INCORRECT)
            }
        }
    }
    val writtenAnswers = remember { mutableStateMapOf<Long, String>() }
    val llmExplanations = remember { mutableStateMapOf<Long, String>() }
    val llmExplanationErrors = remember { mutableStateMapOf<Long, String>() }
    val llmExplanationLoading = remember { mutableStateMapOf<Long, Boolean>() }
    var guessedQuestionIds by remember(initialGuessedQuestionIds) { mutableStateOf(initialGuessedQuestionIds) }
    val loadingChunks = remember { mutableStateMapOf<Int, Boolean>() }
    val safeInitialPage = initialPage.coerceIn(0, (totalQuestionCount - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitialPage, pageCount = { totalQuestionCount.coerceAtLeast(0) })
    var showQuestionNavigator by remember { mutableStateOf(false) }
    val navigatorQuestionTypes = remember(initialQuestions, totalQuestionCount) {
        mutableStateMapOf<Int, QuestionType>().apply {
            initialQuestions.forEach { question -> put(question.position, question.type) }
        }
    }
    var navigatorTypesLoading by remember { mutableStateOf(false) }
    var navigatorTypesLoadFinished by remember(initialQuestions, totalQuestionCount) {
        mutableStateOf(navigatorQuestionTypes.size >= totalQuestionCount)
    }
    var navigatorTypesLoadFailed by remember { mutableStateOf(false) }

    suspend fun loadChunk(start: Int) {
        val normalizedStart = start.coerceAtLeast(0)
        if (normalizedStart >= totalQuestionCount || loadingChunks[normalizedStart] == true) return
        if ((normalizedStart until minOf(normalizedStart + questionPageSize, totalQuestionCount)).all(loadedQuestions::containsKey)) return
        loadingChunks[normalizedStart] = true
        runCatching { onLoadPage(normalizedStart, questionPageSize) }
            .onSuccess { page -> page.forEach { loadedQuestions[it.position] = it } }
        loadingChunks.remove(normalizedStart)
    }

    LaunchedEffect(pagerState.currentPage, totalQuestionCount) {
        if (totalQuestionCount == 0) return@LaunchedEffect
        val current = pagerState.currentPage
        onPageChanged(current)
        val chunkStart = current / questionPageSize * questionPageSize
        loadChunk(chunkStart)
        if (current - chunkStart >= questionPageSize - 10) loadChunk(chunkStart + questionPageSize)
        if (current - chunkStart <= 6) loadChunk(chunkStart - questionPageSize)

        val keepFrom = (chunkStart - questionPageSize).coerceAtLeast(0)
        val keepUntil = chunkStart + questionPageSize * 2
        loadedQuestions.keys.filter { it < keepFrom || it >= keepUntil }.forEach(loadedQuestions::remove)
    }

    fun goToPage(page: Int) {
        if (page !in 0 until totalQuestionCount) return
        scope.launch {
            if (abs(page - pagerState.currentPage) <= 6) pagerState.animateScrollToPage(page)
            else pagerState.scrollToPage(page)
        }
    }

    fun openQuestionNavigator() {
        showQuestionNavigator = true
        if (navigatorTypesLoading || navigatorQuestionTypes.size >= totalQuestionCount) return
        scope.launch {
            navigatorTypesLoading = true
            navigatorTypesLoadFinished = false
            navigatorTypesLoadFailed = false
            for (start in 0 until totalQuestionCount step questionPageSize) {
                val end = minOf(start + questionPageSize, totalQuestionCount)
                if ((start until end).all(navigatorQuestionTypes::containsKey)) continue
                val pageResult = runCatching { onLoadPage(start, questionPageSize) }
                if (pageResult.isFailure) {
                    navigatorTypesLoadFailed = true
                    break
                }
                pageResult.getOrThrow().forEach { question ->
                    navigatorQuestionTypes[question.position] = question.type
                }
            }
            navigatorTypesLoading = false
            navigatorTypesLoadFinished = true
        }
    }

    fun submitAnswer(question: QuizQuestion, page: Int, labels: Set<String>) {
        val correctLabels = question.correctLabels
        val isCorrect = labels == correctLabels && correctLabels.isNotEmpty()
        answerStates[question.id] = ChoiceAnswer(labels, submitted = true)
        statusByPosition[page] = if (isCorrect) QuestionAnswerStatus.CORRECT else QuestionAnswerStatus.INCORRECT
        onAnswer(question, page, labels, isCorrect)
        if (isCorrect && singleChoiceAutoNext && page < totalQuestionCount - 1) {
            scope.launch {
                delay(520)
                pagerState.animateScrollToPage(page + 1)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(bankName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            "已做 ${statusByPosition.size} / $totalQuestionCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "退出答题")
                    }
                },
                actions = {
                    IconButton(onClick = ::openQuestionNavigator) {
                        Icon(Icons.Rounded.GridView, contentDescription = "题目列表")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        if (totalQuestionCount == 0) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { Text("暂无题目") }
            return@Scaffold
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 18.dp),
            pageSpacing = 18.dp,
            beyondViewportPageCount = 1,
        ) { page ->
            val question = loadedQuestions[page]
            if (question == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val answerState = answerStates[question.id] ?: ChoiceAnswer()
                QuizQuestionPage(
                    question = question,
                    answerState = answerState,
                    autoNext = singleChoiceAutoNext,
                    reviewMode = reviewMode,
                    isLastQuestion = page == totalQuestionCount - 1,
                    writtenAnswer = writtenAnswers[question.id].orEmpty(),
                    onWrittenAnswerChange = { writtenAnswers[question.id] = it },
                    onSelectionChange = { labels -> answerStates[question.id] = ChoiceAnswer(labels, submitted = false) },
                    onSubmit = { labels -> submitAnswer(question, page, labels) },
                    onNext = { goToPage(page + 1) },
                    isGuessed = question.id in guessedQuestionIds,
                    onGuessedChange = { guessed ->
                        guessedQuestionIds = if (guessed) guessedQuestionIds + question.id else guessedQuestionIds - question.id
                        onGuessChanged(question.id, guessed)
                    },
                    llmConfigured = llmConfigured,
                    llmExplanation = llmExplanations[question.id],
                    llmExplanationError = llmExplanationErrors[question.id],
                    llmExplanationLoading = llmExplanationLoading[question.id] == true,
                    onAskLlm = {
                        if (llmExplanationLoading[question.id] == true) return@QuizQuestionPage
                        scope.launch {
                            llmExplanationErrors.remove(question.id)
                            llmExplanationLoading[question.id] = true
                            runCatching { onAskLlm(question) }
                                .onSuccess { explanation -> llmExplanations[question.id] = explanation }
                                .onFailure { error ->
                                    llmExplanationErrors[question.id] = error.message ?: "大模型解析失败"
                                }
                            llmExplanationLoading.remove(question.id)
                        }
                    },
                    onFinish = onFinish,
                )
            }
        }
    }

    if (showQuestionNavigator) {
        QuestionNavigatorDialog(
            totalQuestionCount = totalQuestionCount,
            currentPage = pagerState.currentPage,
            statusByPosition = statusByPosition,
            questionTypeByPosition = navigatorQuestionTypes,
            typesLoading = navigatorTypesLoading,
            typesLoadFinished = navigatorTypesLoadFinished,
            typesLoadFailed = navigatorTypesLoadFailed,
            onDismiss = { showQuestionNavigator = false },
            onQuestionClick = { page ->
                showQuestionNavigator = false
                goToPage(page)
            },
        )
    }
}

@Composable
private fun QuizQuestionPage(
    question: QuizQuestion,
    answerState: ChoiceAnswer,
    autoNext: Boolean,
    reviewMode: Boolean,
    isLastQuestion: Boolean,
    writtenAnswer: String,
    onWrittenAnswerChange: (String) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onSubmit: (Set<String>) -> Unit,
    onNext: () -> Unit,
    isGuessed: Boolean,
    onGuessedChange: (Boolean) -> Unit,
    llmConfigured: Boolean,
    llmExplanation: String?,
    llmExplanationError: String?,
    llmExplanationLoading: Boolean,
    onAskLlm: () -> Unit,
    onFinish: () -> Unit,
) {
    val multiple = question.type == QuestionType.MULTIPLE_CHOICE
    val isChoice = question.options.isNotEmpty()
    val isCorrect = answerState.submitted && answerState.selectedLabels == question.correctLabels && question.correctLabels.isNotEmpty()
    val showGuessedMark = answerState.submitted || isGuessed
    val showNext = isChoice && answerState.submitted && (reviewMode || !isCorrect || !autoNext)
    val showDisabledNext = isChoice && !multiple && !autoNext && !answerState.submitted && !reviewMode && !isLastQuestion
    val showSubmit = multiple && !answerState.submitted && !reviewMode
    val showFinish = isLastQuestion && (answerState.submitted || reviewMode)
    val showAction = showNext || showDisabledNext || showSubmit || showFinish

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = if (showAction) 112.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            QuestionHeading(
                type = question.type,
                questionNumber = question.position + 1,
                prompt = question.prompt,
                showGuessedMark = showGuessedMark,
                isGuessed = isGuessed,
                onGuessedChange = onGuessedChange,
            )
            Spacer(Modifier.height(2.dp))
            if (isChoice) {
                question.options.forEach { option ->
                    val selected = option.label in answerState.selectedLabels
                    val background = when {
                        answerState.submitted && option.isCorrect -> MaterialTheme.colorScheme.primaryContainer
                        answerState.submitted && selected -> MaterialTheme.colorScheme.errorContainer
                        selected -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    }
                    val foreground = when {
                        answerState.submitted && option.isCorrect -> MaterialTheme.colorScheme.onPrimaryContainer
                        answerState.submitted && selected -> MaterialTheme.colorScheme.onErrorContainer
                        selected -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val optionShape = RoundedCornerShape(20.dp)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(optionShape)
                            .clickable(enabled = !answerState.submitted && !reviewMode) {
                                if (multiple) {
                                    val updated = if (selected) {
                                        answerState.selectedLabels - option.label
                                    } else {
                                        answerState.selectedLabels + option.label
                                    }
                                    onSelectionChange(updated)
                                } else {
                                    onSubmit(setOf(option.label))
                                }
                            },
                        shape = optionShape,
                        color = background,
                        contentColor = foreground,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OptionSelectionControl(
                                multiple = multiple,
                                selected = selected,
                                submitted = answerState.submitted,
                                correct = option.isCorrect,
                                reviewMode = reviewMode,
                                color = foreground,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${option.label}. ${option.text}",
                                modifier = Modifier.weight(1f),
                                color = foreground,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (answerState.submitted) {
                    AnswerFeedback(
                        question = question,
                        isCorrect = isCorrect,
                        llmConfigured = llmConfigured,
                        llmExplanation = llmExplanation,
                        llmExplanationError = llmExplanationError,
                        llmExplanationLoading = llmExplanationLoading,
                        onAskLlm = onAskLlm,
                    )
                } else if (reviewMode) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text("本题当时未作答", modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            } else {
                TextField(
                    value = writtenAnswer,
                    onValueChange = onWrittenAnswerChange,
                    enabled = !reviewMode,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("你的答案") },
                    minLines = 5,
                )
            }
        }

        if (showAction) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showSubmit) {
                        Button(
                            onClick = { onSubmit(answerState.selectedLabels) },
                            enabled = answerState.selectedLabels.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        ) { Text("提交") }
                    } else {
                        Button(
                            onClick = { if (isLastQuestion) onFinish() else onNext() },
                            enabled = isLastQuestion || answerState.submitted,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        ) {
                            Text(if (isLastQuestion) if (reviewMode) "返回记录" else "完成" else "下一题")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionSelectionControl(
    multiple: Boolean,
    selected: Boolean,
    submitted: Boolean,
    correct: Boolean,
    reviewMode: Boolean,
    color: Color,
) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (submitted && (correct || selected)) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = if (multiple) RoundedCornerShape(5.dp) else CircleShape,
                color = Color.Transparent,
                border = BorderStroke(2.dp, color),
                contentColor = color,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (correct) Icons.Rounded.Check else Icons.Rounded.Close,
                        contentDescription = if (correct) "正确选项" else "错误选择",
                        modifier = Modifier.size(17.dp),
                        tint = color,
                    )
                }
            }
        } else if (multiple) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = !submitted && !reviewMode,
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = !submitted && !reviewMode,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionHeading(
    type: QuestionType,
    questionNumber: Int,
    prompt: String,
    showGuessedMark: Boolean,
    isGuessed: Boolean,
    onGuessedChange: (Boolean) -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(
                "${type.displayName} · 第 $questionNumber 题",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            if (showGuessedMark) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Surface(
                        onClick = { onGuessedChange(!isGuessed) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isGuessed) Color(0xFFFFE082) else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (isGuessed) Color(0xFF4A3A00) else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (isGuessed) Color(0xFFE1B93F) else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            text = if (isGuessed) "蒙对了！" else "蒙对了？",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Text(
                prompt,
                modifier = Modifier.padding(end = 14.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val typePlaceable = measurables.first().measure(looseConstraints)
        val promptPlaceable = measurables.last().measure(looseConstraints)
        val guessedPlaceable = if (showGuessedMark) measurables[1].measure(looseConstraints) else null
        val width = constraints.maxWidth
        val promptY = typePlaceable.height + 18.dp.roundToPx()
        layout(width, promptY + promptPlaceable.height) {
            typePlaceable.placeRelative(0, 0)
            guessedPlaceable?.placeRelative(width - guessedPlaceable.width, 0)
            promptPlaceable.placeRelative(0, promptY)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionNavigatorDialog(
    totalQuestionCount: Int,
    currentPage: Int,
    statusByPosition: Map<Int, QuestionAnswerStatus>,
    questionTypeByPosition: Map<Int, QuestionType>,
    typesLoading: Boolean,
    typesLoadFinished: Boolean,
    typesLoadFailed: Boolean,
    onDismiss: () -> Unit,
    onQuestionClick: (Int) -> Unit,
) {
    val groupedPages = QuestionType.entries
        .sortedBy { it.navigatorOrder }
        .mapNotNull { type ->
            (0 until totalQuestionCount)
                .filter { page -> (questionTypeByPosition[page] ?: QuestionType.UNKNOWN) == type }
                .takeIf(List<Int>::isNotEmpty)
                ?.let { type to it }
        }
    val gridState = rememberLazyGridState()
    val currentGridItemIndex = groupedPages.navigatorItemIndex(currentPage)

    LaunchedEffect(typesLoadFinished, currentGridItemIndex) {
        if (typesLoadFinished && currentGridItemIndex >= 0) {
            gridState.scrollToItem(currentGridItemIndex)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("题目列表")
                                Text(
                                    "已答 ${statusByPosition.size} / $totalQuestionCount",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回答题")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
            ) { innerPadding ->
                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        NavigatorLegend("答对", MaterialTheme.colorScheme.primary)
                        NavigatorLegend("答错", MaterialTheme.colorScheme.error)
                        NavigatorLegend("未答", MaterialTheme.colorScheme.surfaceVariant)
                    }
                    if (!typesLoadFinished) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (typesLoading) "正在整理题型…" else "正在准备题目列表…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        if (typesLoadFailed) {
                            Text(
                                "部分题型信息加载失败，已归入“未指定”。",
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(50.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 18.dp, top = 2.dp, end = 18.dp, bottom = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            groupedPages.forEachIndexed { groupIndex, (type, pages) ->
                                item(
                                    key = "type-${type.name}",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    Column {
                                        if (groupIndex > 0) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                type.displayName,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            ) {
                                                Text(
                                                    "${pages.size} 题",
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                    }
                                }
                                items(pages, key = { it }) { page ->
                                    val status = statusByPosition[page]
                                    val container = when (status) {
                                        QuestionAnswerStatus.CORRECT -> MaterialTheme.colorScheme.primary
                                        QuestionAnswerStatus.INCORRECT -> MaterialTheme.colorScheme.error
                                        null -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                    val content = when (status) {
                                        QuestionAnswerStatus.CORRECT -> MaterialTheme.colorScheme.onPrimary
                                        QuestionAnswerStatus.INCORRECT -> MaterialTheme.colorScheme.onError
                                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        onClick = { onQuestionClick(page) },
                                        modifier = Modifier.aspectRatio(1f),
                                        shape = RoundedCornerShape(14.dp),
                                        color = container,
                                        contentColor = content,
                                        border = if (page == currentPage) {
                                            BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                                        } else {
                                            null
                                        },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "${page + 1}",
                                                fontWeight = if (page == currentPage) FontWeight.Bold else FontWeight.Medium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun List<Pair<QuestionType, List<Int>>>.navigatorItemIndex(page: Int): Int {
    var itemIndex = 0
    for ((_, pages) in this) {
        val pageIndex = pages.indexOf(page)
        if (pageIndex >= 0) return itemIndex + 1 + pageIndex
        itemIndex += 1 + pages.size
    }
    return -1
}

private val QuestionType.navigatorOrder: Int
    get() = when (this) {
        QuestionType.SINGLE_CHOICE -> 0
        QuestionType.MULTIPLE_CHOICE -> 1
        QuestionType.TRUE_FALSE -> 2
        QuestionType.CALCULATION -> 3
        QuestionType.SHORT_ANSWER -> 4
        QuestionType.UNKNOWN -> 5
    }

@Composable
private fun NavigatorLegend(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnswerFeedback(
    question: QuizQuestion,
    isCorrect: Boolean,
    llmConfigured: Boolean,
    llmExplanation: String?,
    llmExplanationError: String?,
    llmExplanationLoading: Boolean,
    onAskLlm: () -> Unit,
) {
    val feedbackContentColor = if (isCorrect) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isCorrect) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = feedbackContentColor,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (isCorrect) "回答正确" else "回答错误",
                fontWeight = FontWeight.SemiBold,
                color = if (isCorrect) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            )
            Text("正确答案：${question.correctAnswerText}")
            Text("解析：${question.explanation?.takeIf(String::isNotBlank) ?: "暂无"}")
            OutlinedButton(
                onClick = onAskLlm,
                enabled = llmConfigured && !llmExplanationLoading,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                if (llmExplanationLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (llmExplanationLoading) "正在生成解析…" else "问大模型")
            }
            if (!llmConfigured) {
                Text(
                    "请先在设置中配置大模型接口",
                    style = MaterialTheme.typography.bodySmall,
                    color = feedbackContentColor,
                )
            }
            llmExplanation?.let { explanation ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("大模型解析", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(explanation, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            llmExplanationError?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.error,
                ) {
                    Text("解析失败：$error", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val QuizQuestion.correctLabels: Set<String>
    get() = options.filter { it.isCorrect }.mapTo(linkedSetOf()) { it.label }

private val QuizQuestion.correctAnswerText: String
    get() = options.filter { it.isCorrect }
        .joinToString("，") { "${it.label}. ${it.text}" }
        .ifBlank { answer.ifBlank { "未提供" } }
