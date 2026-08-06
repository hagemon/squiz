package com.hagemon.squiz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hagemon.squiz.data.SquizRepository
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.PracticeMode
import com.hagemon.squiz.model.PracticeSession
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeHistoryScreen(
    bank: LibraryItem,
    repository: SquizRepository,
    onBack: () -> Unit,
    onOpenSession: (PracticeSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sessions by repository.observePracticeSessions(bank.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sessions) {
        selectedIds = selectedIds.intersect(sessions.mapTo(mutableSetOf(), PracticeSession::id))
        if (sessions.isEmpty()) selectionMode = false
    }

    fun leaveSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) "已选择 ${selectedIds.size} 项" else "练习记录") },
                navigationIcon = {
                    IconButton(onClick = { if (selectionMode) leaveSelection() else onBack() }) {
                        Icon(
                            if (selectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (selectionMode) "退出选择" else "返回",
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                selectedIds = if (selectedIds.size == sessions.size) emptySet()
                                else sessions.mapTo(linkedSetOf(), PracticeSession::id)
                            },
                        ) {
                            Icon(
                                if (selectedIds.size == sessions.size) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                                contentDescription = if (selectedIds.size == sessions.size) "取消全选" else "全选",
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }, enabled = selectedIds.isNotEmpty()) {
                            Icon(Icons.Rounded.Delete, contentDescription = "删除所选记录")
                        }
                    } else if (sessions.isNotEmpty()) {
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Rounded.Checklist, contentDescription = "管理记录")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (selectionMode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
                    titleContentColor = if (selectionMode) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = if (selectionMode) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = if (selectionMode) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("暂无练习记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = PracticeSession::id) { session ->
                    val selected = session.id in selectedIds
                    SessionCard(
                        session = session,
                        selected = selected,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (selected) selectedIds - session.id else selectedIds + session.id
                            } else {
                                onOpenSession(session)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text("删除 ${selectedIds.size} 条练习记录？") },
            text = { Text("记录中的作答详情会一并删除，错题次数会重新计算，由这些记录产生的蒙对标记也会取消。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds
                        showDeleteDialog = false
                        scope.launch {
                            repository.deletePracticeSessions(ids)
                            leaveSelection()
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionCard(
    session: PracticeSession,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                when (session.mode) {
                    PracticeMode.WRONG -> Icons.Rounded.ErrorOutline
                    PracticeMode.GUESSED -> Icons.Rounded.Casino
                    PracticeMode.NORMAL -> Icons.Rounded.PlayCircle
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (session.mode) {
                        PracticeMode.WRONG -> "错题练习"
                        PracticeMode.GUESSED -> "蒙对练习"
                        PracticeMode.NORMAL -> "普通练习"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    formatSessionTime(session.startedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${if (session.isCompleted) "已完成" else "未完成"} · ${session.answeredCount}/${session.totalQuestions} 已答 · ${session.correctCount} 正确",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (session.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
