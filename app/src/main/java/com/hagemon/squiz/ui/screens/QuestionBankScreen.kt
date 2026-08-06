package com.hagemon.squiz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hagemon.squiz.data.SquizRepository
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.PracticeMode
import com.hagemon.squiz.model.PracticeSession
import com.hagemon.squiz.model.QuestionType
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuestionBankScreen(
    bank: LibraryItem,
    repository: SquizRepository,
    onBack: () -> Unit,
    onStartNew: () -> Unit,
    onContinue: (PracticeSession) -> Unit,
    onStartWrong: (minimumWrongCount: Int) -> Unit,
    onStartGuessed: () -> Unit,
    onOpenHistory: () -> Unit,
    onStartRandom: (types: Set<QuestionType>, count: Int) -> Unit,
) {
    val sessions by repository.observePracticeSessions(bank.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unfinished = sessions.firstOrNull { it.mode == PracticeMode.NORMAL && !it.isCompleted }
    var wrongLevel by remember { mutableIntStateOf(1) }
    var wrongCount by remember { mutableIntStateOf(0) }
    var maximumWrongCount by remember { mutableIntStateOf(0) }
    var guessedCount by remember { mutableIntStateOf(0) }
    var randomCountText by remember(bank.questionCount) {
        mutableStateOf(minOf(20, bank.questionCount.coerceAtLeast(1)).toString())
    }
    var selectedTypes by remember {
        mutableStateOf(setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE))
    }

    LaunchedEffect(bank.id, sessions) {
        maximumWrongCount = repository.getMaximumWrongCount(bank.id)
        guessedCount = repository.getGuessedQuestionCount(bank.id)
        wrongLevel = if (maximumWrongCount == 0) 1 else wrongLevel.coerceIn(1, maximumWrongCount)
    }
    LaunchedEffect(bank.id, wrongLevel, maximumWrongCount, sessions) {
        wrongCount = if (maximumWrongCount == 0) 0 else repository.getWrongQuestionCount(bank.id, wrongLevel)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(bank.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${bank.questionCount} 题",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PracticeCard(
                    icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    title = "练习",
                    supporting = if (unfinished == null) {
                        "按题库顺序开始一轮新的练习"
                    } else {
                        "有一轮练习尚未完成，可从第 ${unfinished.currentPosition + 1} 题继续"
                    },
                ) {
                    if (unfinished != null) {
                        Button(onClick = { onContinue(unfinished) }, modifier = Modifier.fillMaxWidth()) {
                            Text("继续练习 · ${formatSessionTime(unfinished.startedAt)}")
                        }
                        TextButton(onClick = onStartNew, modifier = Modifier.align(Alignment.End)) {
                            Text("开始新的练习")
                        }
                    } else {
                        Button(onClick = onStartNew, modifier = Modifier.fillMaxWidth()) {
                            Text("开始练习")
                        }
                    }
                }
            }

            item {
                PracticeCard(
                    icon = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null) },
                    title = "错题练习",
                    supporting = "从历史错误中筛选题目，练习会单独保存为错题记录",
                ) {
                    if (maximumWrongCount == 0) {
                        Text(
                            "暂无错题",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("至少错 $wrongLevel 次", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text("$wrongCount 题", color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = wrongLevel.toFloat(),
                            onValueChange = { wrongLevel = it.roundToInt().coerceIn(1, maximumWrongCount) },
                            valueRange = 0f..maximumWrongCount.toFloat(),
                            steps = maximumWrongCount - 1,
                        )
                        Button(
                            onClick = { onStartWrong(wrongLevel) },
                            enabled = wrongCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (wrongCount > 0) "练习这 $wrongCount 道错题" else "当前等级暂无错题")
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("蒙对的题", fontWeight = FontWeight.Medium)
                            Text(
                                "共 $guessedCount 题",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onStartGuessed, enabled = guessedCount > 0) {
                            Text("开始练习")
                        }
                    }
                }
            }

            item {
                PracticeCard(
                    icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    title = "练习记录",
                    supporting = if (sessions.isEmpty()) "还没有练习记录" else "共 ${sessions.size} 条记录，未完成的可继续练习",
                ) {
                    OutlinedButton(
                        onClick = onOpenHistory,
                        enabled = sessions.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看记录")
                    }
                }
            }

            item {
                val maximum = bank.questionCount.coerceAtLeast(1)
                val randomCount = randomCountText.toIntOrNull()
                val validCount = randomCount != null && randomCount in 1..maximum
                PracticeCard(
                    icon = { Icon(Icons.Rounded.Shuffle, contentDescription = null) },
                    title = "随机练习",
                    supporting = "即时抽题，不生成练习记录",
                ) {
                    Text("题型", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        randomTypes.forEach { type ->
                            FilterChip(
                                selected = type in selectedTypes,
                                onClick = {
                                    selectedTypes = if (type in selectedTypes) selectedTypes - type else selectedTypes + type
                                },
                                label = { Text(type.displayName) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = randomCountText,
                        onValueChange = { value -> randomCountText = value.filter(Char::isDigit).take(4) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("题目数量") },
                        suffix = { Text("题") },
                        singleLine = true,
                        isError = randomCountText.isNotEmpty() && !validCount,
                        supportingText = { Text("可输入 1～$maximum") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    )
                    Button(
                        onClick = { onStartRandom(selectedTypes, requireNotNull(randomCount)) },
                        enabled = selectedTypes.isNotEmpty() && bank.questionCount > 0 && validCount,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Quiz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始随机练习")
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticeCard(
    icon: @Composable () -> Unit,
    title: String,
    supporting: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                androidx.compose.material3.Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { icon() }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

private val randomTypes = listOf(
    QuestionType.SINGLE_CHOICE,
    QuestionType.MULTIPLE_CHOICE,
    QuestionType.TRUE_FALSE,
)

internal fun formatSessionTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
