package com.hagemon.squiz.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hagemon.squiz.data.SquizRepository
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.LibraryItemType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveItemScreen(
    item: LibraryItem,
    repository: SquizRepository,
    onBack: () -> Unit,
    onMoved: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by repository.libraryItems.collectAsStateWithLifecycle(initialValue = emptyList())
    var currentFolderId by rememberSaveable(item.id) { mutableStateOf(item.parentId) }
    val excludedIds = remember(items, item) {
        if (item.type == LibraryItemType.FOLDER) collectDescendantFolderIds(items, item.id) + item.id else emptySet()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text("移动“${item.name}”", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { repository.moveItem(item, currentFolderId) }
                                .onSuccess { onMoved() }
                                .onFailure { onError(it.message ?: "移动失败") }
                        }
                    },
                    enabled = currentFolderId != item.parentId && currentFolderId !in excludedIds,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                    Text("移动到此处", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
    ) { innerPadding ->
        FolderPicker(
            items = items,
            currentFolderId = currentFolderId,
            onFolderChange = { currentFolderId = it },
            onCreateFolder = { name, parentId -> scope.launch { repository.addFolder(name, parentId) } },
            excludedFolderIds = excludedIds,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

private fun collectDescendantFolderIds(items: List<LibraryItem>, folderId: String): Set<String> {
    val childrenByParent = items.filter { it.type == LibraryItemType.FOLDER }.groupBy(LibraryItem::parentId)
    val result = mutableSetOf<String>()
    val pending = ArrayDeque<String>().apply { add(folderId) }
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        childrenByParent[current].orEmpty().forEach { child ->
            if (result.add(child.id)) pending.add(child.id)
        }
    }
    return result
}
