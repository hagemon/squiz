package com.hagemon.squiz.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hagemon.squiz.data.SquizRepository
import com.hagemon.squiz.data.excel.QuestionBankExporter
import com.hagemon.squiz.model.BankPracticeSettings
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.LibraryItemType
import com.hagemon.squiz.model.activityAt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuestionManagerDialog(
    bank: LibraryItem,
    repository: SquizRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val libraryItems by repository.libraryItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val referenceBanks = libraryItems
        .filter { it.type == LibraryItemType.QUESTION_BANK && it.id != bank.id }
        .sortedByDescending(LibraryItem::activityAt)
    var bankPickerAction by remember(bank.id) { mutableStateOf<BankPickerAction?>(null) }
    var pickerSelectedBankIds by remember(bank.id) { mutableStateOf<Set<String>>(emptySet()) }
    var showAllPickerBanks by remember(bank.id) { mutableStateOf(false) }
    var visibleQuestionCount by remember(bank.id) { mutableIntStateOf(0) }
    var hiddenQuestionCount by remember(bank.id) { mutableIntStateOf(0) }
    var countsLoaded by remember(bank.id) { mutableStateOf(false) }
    var practiceSettings by remember(bank.id) { mutableStateOf(BankPracticeSettings()) }
    var settingsLoaded by remember(bank.id) { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var wrongQuestionCount by remember(bank.id) { mutableIntStateOf(0) }
    var wrongQuestionCountLoaded by remember(bank.id) { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<QuestionOperation?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val busy = operation != null || exportBusy

    val wrongQuestionExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-excel"),
    ) { uri ->
        if (uri != null) scope.launch {
            exportBusy = true
            runCatching {
                QuestionBankExporter.export(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    questions = repository.getWrongQuestions(bank.id),
                )
            }.onSuccess {
                snackbarHostState.showSnackbar("已导出 $wrongQuestionCount 道错题")
            }.onFailure { error ->
                snackbarHostState.showSnackbar(error.message ?: "错题导出失败")
            }
            exportBusy = false
        }
    }

    LaunchedEffect(bank.id, refreshToken) {
        visibleQuestionCount = repository.getVisibleQuestionCount(bank.id)
        hiddenQuestionCount = repository.getHiddenQuestionCount(bank.id)
        countsLoaded = true
    }

    LaunchedEffect(bank.id) {
        practiceSettings = repository.getBankPracticeSettings(bank.id)
        settingsLoaded = true
    }

    LaunchedEffect(bank.id, refreshToken) {
        wrongQuestionCount = repository.getWrongQuestionCount(bank.id, minimumWrongCount = 1)
        wrongQuestionCountLoaded = true
    }

    fun runCountOperation(
        task: suspend () -> Int,
        emptyMessage: String,
        successMessage: (Int) -> String,
        failureMessage: String,
    ) {
        scope.launch {
            val result = runCatching { task() }
            operation = null
            result
                .onSuccess { count ->
                    refreshToken++
                    snackbarHostState.showSnackbar(if (count == 0) emptyMessage else successMessage(count))
                }
                .onFailure { error ->
                    snackbarHostState.showSnackbar(error.message ?: failureMessage)
                }
        }
    }

    fun runBankCombination(sourceBankId: String, crossOnly: Boolean) {
        scope.launch {
            val result = runCatching {
                if (crossOnly) {
                    repository.crossQuestionBank(bank.id, sourceBankId)
                } else {
                    repository.mergeQuestionBank(bank.id, sourceBankId)
                }
            }
            operation = null
            result
                .onSuccess { mergeResult ->
                    refreshToken++
                    if (crossOnly) {
                        val changes = buildList {
                            if (mergeResult.mergedSessions > 0) add("同步 ${mergeResult.mergedSessions} 次练习")
                            if (mergeResult.mergedAnswers > 0) {
                                add("${mergeResult.mergedAnswers} 条作答（答错 ${mergeResult.mergedWrongAnswers} 条）")
                            }
                            if (mergeResult.mergedGuessedQuestions > 0) {
                                add("${mergeResult.mergedGuessedQuestions} 个蒙对标记")
                            }
                        }
                        snackbarHostState.showSnackbar(
                            when {
                                mergeResult.overlappingQuestions == 0 -> "两个题库没有可关联的重合题"
                                changes.isEmpty() -> "找到 ${mergeResult.overlappingQuestions} 道重合题，练习记录均已关联过"
                                else -> "已关联 ${mergeResult.overlappingQuestions} 道重合题，${changes.joinToString("，")}"
                            },
                        )
                    } else {
                        val changes = buildList {
                            if (mergeResult.addedQuestions > 0) add("新增 ${mergeResult.addedQuestions} 道题")
                            if (mergeResult.mergedSessions > 0) add("合并 ${mergeResult.mergedSessions} 次练习")
                            if (mergeResult.mergedAnswers > 0) {
                                add("${mergeResult.mergedAnswers} 条作答（答错 ${mergeResult.mergedWrongAnswers} 条）")
                            }
                            if (mergeResult.mergedGuessedQuestions > 0) {
                                add("${mergeResult.mergedGuessedQuestions} 个蒙对标记")
                            }
                        }
                        snackbarHostState.showSnackbar(
                            if (changes.isEmpty()) "题目和作答记录均已合并过" else "已${changes.joinToString("，")}",
                        )
                    }
                }
                .onFailure { error ->
                    snackbarHostState.showSnackbar(error.message ?: if (crossOnly) "题库交叉失败" else "题库合并失败")
                }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("题目管理")
                                Text(
                                    bank.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !busy) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回题库")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("做题顺序", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("打乱题目顺序", fontWeight = FontWeight.Medium)
                                        Text(
                                            "只影响错题练习和蒙对的题",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = practiceSettings.shuffleReviewQuestions,
                                        enabled = settingsLoaded && !settingsBusy && !busy,
                                        onCheckedChange = { enabled ->
                                            val previous = practiceSettings
                                            practiceSettings = practiceSettings.copy(shuffleReviewQuestions = enabled)
                                            settingsBusy = true
                                            scope.launch {
                                                runCatching { repository.setShuffleReviewQuestions(bank.id, enabled) }
                                                    .onFailure { error ->
                                                        practiceSettings = previous
                                                        snackbarHostState.showSnackbar(error.message ?: "保存设置失败")
                                                    }
                                                settingsBusy = false
                                            }
                                        },
                                    )
                                }
                                HorizontalDivider()
                                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text("普通练习与错题练习排序", fontWeight = FontWeight.Medium)
                                    Text(
                                        "题型优先时按单选、多选、判断排序，同题型内仍按原序号。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = !practiceSettings.typeFirstOrdering,
                                            onClick = {
                                                if (!settingsBusy && practiceSettings.typeFirstOrdering) {
                                                    val previous = practiceSettings
                                                    practiceSettings = practiceSettings.copy(typeFirstOrdering = false)
                                                    settingsBusy = true
                                                    scope.launch {
                                                        runCatching { repository.setTypeFirstOrdering(bank.id, false) }
                                                            .onFailure { error ->
                                                                practiceSettings = previous
                                                                snackbarHostState.showSnackbar(error.message ?: "保存设置失败")
                                                            }
                                                        settingsBusy = false
                                                    }
                                                }
                                            },
                                            enabled = settingsLoaded && !settingsBusy && !busy,
                                            label = { Text("序号优先") },
                                        )
                                        FilterChip(
                                            selected = practiceSettings.typeFirstOrdering,
                                            onClick = {
                                                if (!settingsBusy && !practiceSettings.typeFirstOrdering) {
                                                    val previous = practiceSettings
                                                    practiceSettings = practiceSettings.copy(typeFirstOrdering = true)
                                                    settingsBusy = true
                                                    scope.launch {
                                                        runCatching { repository.setTypeFirstOrdering(bank.id, true) }
                                                            .onFailure { error ->
                                                                practiceSettings = previous
                                                                snackbarHostState.showSnackbar(error.message ?: "保存设置失败")
                                                            }
                                                        settingsBusy = false
                                                    }
                                                }
                                            },
                                            enabled = settingsLoaded && !settingsBusy && !busy,
                                            label = { Text("题型优先（默认）") },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            "题库处理",
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    item {
                        BankActionCard(
                            icon = { Icon(Icons.Rounded.Merge, contentDescription = null) },
                            title = "合并题库",
                            description = "将另一个题库的新题加入当前题库，同时合并重合题的练习、错题和蒙对记录。",
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    pickerSelectedBankIds = emptySet()
                                    showAllPickerBanks = false
                                    bankPickerAction = BankPickerAction.MERGE
                                },
                                enabled = referenceBanks.isNotEmpty() && !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OperationIcon(
                                    running = operation == QuestionOperation.MERGE,
                                    idleIcon = { Icon(Icons.Rounded.Merge, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (operation == QuestionOperation.MERGE) "正在合并…" else "选择题库并合并")
                            }
                        }
                    }

                    item {
                        BankActionCard(
                            icon = { Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null) },
                            title = "题库交叉",
                            description = "不新增或隐藏题目，只将另一个题库中重合题的练习、错题和蒙对记录关联到当前题库。",
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    pickerSelectedBankIds = emptySet()
                                    showAllPickerBanks = false
                                    bankPickerAction = BankPickerAction.CROSS
                                },
                                enabled = referenceBanks.isNotEmpty() && !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OperationIcon(
                                    running = operation == QuestionOperation.CROSS,
                                    idleIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.CompareArrows,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (operation == QuestionOperation.CROSS) "正在关联…" else "选择题库并交叉")
                            }
                        }
                    }

                    item {
                        BankActionCard(
                            icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                            title = "题库去重",
                            description = "只隐藏重复题，不转移练习记录。隐藏后的题目不会出现在练习中。",
                        ) {
                            Text("当前题库去重", fontWeight = FontWeight.SemiBold)
                            Text(
                                "题干和答案相同的题目只保留第一道。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (countsLoaded) "$visibleQuestionCount 可练习 · $hiddenQuestionCount 已隐藏" else "正在统计题目…",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            FilledTonalButton(
                                onClick = {
                                    operation = QuestionOperation.CURRENT_BANK_DEDUP
                                    runCountOperation(
                                        task = { repository.hideDuplicateQuestions(bank.id) },
                                        emptyMessage = "当前题库没有新的重复题",
                                        successMessage = { "已隐藏 $it 道当前题库重复题" },
                                        failureMessage = "去重失败",
                                    )
                                },
                                enabled = countsLoaded && visibleQuestionCount > 0 && !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OperationIcon(operation == QuestionOperation.CURRENT_BANK_DEDUP)
                                Spacer(Modifier.width(8.dp))
                                Text(if (operation == QuestionOperation.CURRENT_BANK_DEDUP) "正在去重…" else "当前题库去重")
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Text("其他题库去重", fontWeight = FontWeight.SemiBold)
                            Text(
                                "选择其他题库，隐藏当前题库中与它们重复的题目。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(
                                onClick = {
                                    pickerSelectedBankIds = emptySet()
                                    showAllPickerBanks = false
                                    bankPickerAction = BankPickerAction.DEDUP
                                },
                                enabled = referenceBanks.isNotEmpty() && !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OperationIcon(operation == QuestionOperation.REFERENCE_BANKS_DEDUP)
                                Spacer(Modifier.width(8.dp))
                                Text(if (operation == QuestionOperation.REFERENCE_BANKS_DEDUP) "正在去重…" else "选择题库并去重")
                            }
                        }
                    }

                    item {
                        BankActionCard(
                            icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            title = "导出错题",
                            description = "将当前题库中至少答错过 1 次的题目导出为单独的 Excel 文件。",
                        ) {
                            Text(
                                when {
                                    !wrongQuestionCountLoaded -> "正在统计错题…"
                                    wrongQuestionCount == 0 -> "当前没有错题"
                                    else -> "共 $wrongQuestionCount 道错题"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            FilledTonalButton(
                                onClick = {
                                    wrongQuestionExporter.launch("${sanitizeWrongExportFileName(bank.name)}-错题.xls")
                                },
                                enabled = wrongQuestionCountLoaded && wrongQuestionCount > 0 && !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (exportBusy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(if (exportBusy) "正在导出…" else "导出错题")
                            }
                        }
                    }
                }
            }
        }
    }

    bankPickerAction?.let { action ->
        BankPickerDialog(
            action = action,
            banks = referenceBanks,
            selectedBankIds = pickerSelectedBankIds,
            showAll = showAllPickerBanks,
            onSelectedBankIdsChange = { pickerSelectedBankIds = it },
            onShowAllChange = { showAllPickerBanks = it },
            onDismiss = { bankPickerAction = null },
            onConfirm = {
                val selectedBankIds = pickerSelectedBankIds
                bankPickerAction = null
                when (action) {
                    BankPickerAction.MERGE -> selectedBankIds.firstOrNull()?.let { sourceBankId ->
                        operation = QuestionOperation.MERGE
                        runBankCombination(sourceBankId, crossOnly = false)
                    }
                    BankPickerAction.CROSS -> selectedBankIds.firstOrNull()?.let { sourceBankId ->
                        operation = QuestionOperation.CROSS
                        runBankCombination(sourceBankId, crossOnly = true)
                    }
                    BankPickerAction.DEDUP -> if (selectedBankIds.isNotEmpty()) {
                        operation = QuestionOperation.REFERENCE_BANKS_DEDUP
                        runCountOperation(
                            task = { repository.hideQuestionsDuplicatedInBanks(bank.id, selectedBankIds) },
                            emptyMessage = "当前题库没有与所选题库重复的题目",
                            successMessage = { "已隐藏 $it 道与所选题库重复的题目" },
                            failureMessage = "去重失败",
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun BankActionCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun BankPickerDialog(
    action: BankPickerAction,
    banks: List<LibraryItem>,
    selectedBankIds: Set<String>,
    showAll: Boolean,
    onSelectedBankIdsChange: (Set<String>) -> Unit,
    onShowAllChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val allowsMultiple = action == BankPickerAction.DEDUP
    val displayedBanks = if (showAll) banks else banks.take(5)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (action) {
                    BankPickerAction.MERGE -> "选择要合并的题库"
                    BankPickerAction.CROSS -> "选择要交叉的题库"
                    BankPickerAction.DEDUP -> "选择去重参照题库"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (action) {
                        BankPickerAction.MERGE -> "新题和重合题的记录都会并入当前题库。"
                        BankPickerAction.CROSS -> "只同步重合题的记录，不改变题目内容或显示状态。"
                        BankPickerAction.DEDUP -> "可多选；当前题库中与所选题库重复的题会被隐藏。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(displayedBanks, key = LibraryItem::id) { reference ->
                        val selected = reference.id in selectedBankIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectedBankIdsChange(
                                        if (allowsMultiple) {
                                            selectedBankIds.toggle(reference.id)
                                        } else {
                                            setOf(reference.id)
                                        },
                                    )
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (allowsMultiple) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { onSelectedBankIdsChange(selectedBankIds.toggle(reference.id)) },
                                )
                            } else {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onSelectedBankIdsChange(setOf(reference.id)) },
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(reference.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${reference.questionCount} 题",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (banks.size > 5) {
                    TextButton(
                        onClick = { onShowAllChange(!showAll) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(if (showAll) "收起" else "更多")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = selectedBankIds.isNotEmpty()) {
                Text(
                    when (action) {
                        BankPickerAction.MERGE -> "合并"
                        BankPickerAction.CROSS -> "交叉"
                        BankPickerAction.DEDUP -> "去重"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun OperationIcon(
    running: Boolean,
    idleIcon: @Composable () -> Unit = {
        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
    },
) {
    if (running) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
        idleIcon()
    }
}

private enum class QuestionOperation {
    MERGE,
    CROSS,
    CURRENT_BANK_DEDUP,
    REFERENCE_BANKS_DEDUP,
}

private enum class BankPickerAction {
    MERGE,
    CROSS,
    DEDUP,
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

private fun sanitizeWrongExportFileName(name: String): String =
    name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "squiz-题库" }
