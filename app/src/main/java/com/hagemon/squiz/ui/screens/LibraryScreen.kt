package com.hagemon.squiz.ui.screens

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.hagemon.squiz.data.SquizRepository
import com.hagemon.squiz.data.excel.QuestionBankExporter
import com.hagemon.squiz.data.excel.StandardTemplateExporter
import com.hagemon.squiz.model.LibraryItem
import com.hagemon.squiz.model.LibraryItemType
import com.hagemon.squiz.model.activityAt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

private val excelMimeTypes = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "text/csv",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    repository: SquizRepository,
    outerPadding: PaddingValues,
    onImportFile: (Uri, String, String?) -> Unit,
    onOpenBank: (LibraryItem) -> Unit,
    onMoveItem: (LibraryItem) -> Unit,
    onSpeedDialVisibilityChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val items by repository.libraryItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentBanks by repository.recentBanks.collectAsStateWithLifecycle(initialValue = emptyList())

    val pagerState = rememberPagerState(pageCount = { 2 })
    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var speedDialOpen by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showFolderDialog by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var exportTarget by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(speedDialOpen) {
        onSpeedDialVisibilityChange(speedDialOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onSpeedDialVisibilityChange(false) }
    }

    val currentFolder = items.firstOrNull { it.id == currentFolderId }
    val recentItems = remember(recentBanks, searchMode, query) {
        if (searchMode && query.isNotBlank()) {
            recentBanks.filter { it.name.contains(query.trim(), ignoreCase = true) }
        } else recentBanks
    }
    val allItems = remember(items, currentFolderId, searchMode, query) {
        val base = if (searchMode && query.isNotBlank()) {
            items.filter { it.name.contains(query.trim(), ignoreCase = true) }
        } else {
            items.filter { it.parentId == currentFolderId }
        }
        base.sortedWith(
            compareBy<LibraryItem> { it.type != LibraryItemType.FOLDER }
                .thenByDescending { it.createdAt },
        )
    }

    fun closeSearch() {
        searchMode = false
        query = ""
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    LaunchedEffect(searchMode) {
        if (searchMode) {
            delay(60)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = context.contentResolver.queryDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "未命名题库.xls"
            onImportFile(uri, name, currentFolderId.takeIf { pagerState.currentPage == 1 })
        }
    }
    val templateCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-excel"),
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { StandardTemplateExporter.export(context.contentResolver, uri) }
                .onSuccess { snackbarHostState.showSnackbar("标准模板已导出") }
                .onFailure { snackbarHostState.showSnackbar(it.message ?: "模板导出失败") }
        }
    }
    fun exportTemplate() = templateCreator.launch("squiz-标准题库模板.xls")
    val bankExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-excel"),
    ) { uri ->
        val bank = exportTarget
        exportTarget = null
        if (uri != null && bank != null) scope.launch {
            runCatching {
                QuestionBankExporter.export(context.contentResolver, uri, repository.getAllQuestions(bank.id))
            }.onSuccess {
                snackbarHostState.showSnackbar("题库已导出")
            }.onFailure {
                snackbarHostState.showSnackbar(it.message ?: "题库导出失败")
            }
        }
    }

    fun exportBank(bank: LibraryItem) {
        exportTarget = bank
        bankExporter.launch("${sanitizeFileName(bank.name)}.xls")
    }

    fun shareBank(bank: LibraryItem) {
        scope.launch {
            runCatching {
                val shareFile = File(
                    context.cacheDir,
                    "shared_question_banks/${sanitizeFileName(bank.name)}.xls",
                )
                QuestionBankExporter.export(shareFile, repository.getAllQuestions(bank.id))
                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile,
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.ms-excel"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_TITLE, bank.name)
                    clipData = ClipData.newRawUri(bank.name, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, "分享题库"))
            }.onFailure {
                snackbarHostState.showSnackbar(it.message ?: "题库分享失败")
            }
        }
    }

    BackHandler(enabled = currentFolderId != null || searchMode || speedDialOpen) {
        when {
            speedDialOpen -> speedDialOpen = false
            searchMode -> closeSearch()
            currentFolderId != null -> currentFolderId = currentFolder?.parentId
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = outerPadding.calculateBottomPadding()),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            if (searchMode) {
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                                    placeholder = { Text("搜索") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.titleMedium,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    trailingIcon = {
                                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                                        }
                                    },
                                )
                            } else {
                                Text(
                                    if (pagerState.currentPage == 1) currentFolder?.name ?: "题库" else "题库",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        },
                        navigationIcon = {
                            if (searchMode || currentFolderId != null) IconButton(
                                onClick = {
                                    if (searchMode) closeSearch() else currentFolderId = currentFolder?.parentId
                                },
                            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
                        },
                        actions = {
                            if (!searchMode) {
                                IconButton(onClick = { searchMode = true }) {
                                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                                }
                                Box {
                                    IconButton(onClick = { overflowOpen = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                                    }
                                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("导出标准模板") },
                                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                            onClick = { overflowOpen = false; exportTemplate() },
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = MaterialTheme.colorScheme.background,
                        divider = {},
                        indicator = { tabPositions -> ElasticTabIndicator(tabPositions, pagerState) },
                    ) {
                        listOf("最近题库", "全部题库").forEachIndexed { index, title ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(title) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                beyondViewportPageCount = 1,
            ) { page ->
                LibraryPage(
                    pageItems = if (page == 0) recentItems else allItems,
                    allItems = items,
                    showBreadcrumbs = page == 1,
                    currentFolderId = currentFolderId,
                    onNavigateFolder = { currentFolderId = it; closeSearch() },
                    onImport = { importPicker.launch(excelMimeTypes) },
                    onExport = ::exportTemplate,
                    onItemClick = { item ->
                        if (item.type == LibraryItemType.FOLDER) {
                            currentFolderId = item.id
                            closeSearch()
                        } else onOpenBank(item)
                    },
                    onRename = { renameTarget = it },
                    onMove = onMoveItem,
                    onExportBank = ::exportBank,
                    onShareBank = ::shareBank,
                    onDelete = { deleteTarget = it },
                    allowMove = page == 1,
                )
            }
        }

        AnimatedVisibility(visible = speedDialOpen, enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { speedDialOpen = false },
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpeedDialOption(
                visible = speedDialOpen,
                label = "题库",
                icon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
                onClick = { speedDialOpen = false; importPicker.launch(excelMimeTypes) },
            )
            SpeedDialOption(
                visible = speedDialOpen,
                label = "文件夹",
                icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                onClick = {
                    speedDialOpen = false
                    scope.launch { pagerState.animateScrollToPage(1) }
                    showFolderDialog = true
                },
            )
            val rotation by animateFloatAsState(
                if (speedDialOpen) 135f else 0f,
                spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                label = "加号旋转",
            )
            FloatingActionButton(onClick = { speedDialOpen = !speedDialOpen }) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = if (speedDialOpen) "关闭" else "新增",
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
        }
    }

    if (showFolderDialog) NameDialog(
        title = "新建文件夹",
        initialName = "",
        confirmLabel = "创建",
        onDismiss = { showFolderDialog = false },
        onConfirm = { name ->
            showFolderDialog = false
            scope.launch {
                repository.addFolder(name, currentFolderId)
                snackbarHostState.showSnackbar("已创建 $name")
            }
        },
    )
    renameTarget?.let { item ->
        NameDialog(
            title = "重命名",
            initialName = item.name,
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name -> renameTarget = null; scope.launch { repository.renameItem(item, name) } },
        )
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text("删除“${item.name}”？") },
            text = { Text(if (item.type == LibraryItemType.FOLDER) "文件夹中的内容也会一并移除。" else "原 Excel 文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; scope.launch { repository.deleteItem(item) } }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ElasticTabIndicator(
    tabPositions: List<androidx.compose.material3.TabPosition>,
    pagerState: PagerState,
) {
    if (tabPositions.isEmpty()) return
    val continuousPage = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, tabPositions.lastIndex.toFloat())
    val fromIndex = floor(continuousPage).toInt()
    val toIndex = (fromIndex + 1).coerceAtMost(tabPositions.lastIndex)
    val progress = continuousPage - fromIndex
    val from = tabPositions[fromIndex]
    val to = tabPositions[toIndex]
    val center = lerp(from.left + from.width / 2, to.left + to.width / 2, progress)
    val indicatorWidth = 40.dp + 30.dp * sin(PI * progress).toFloat()

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer { translationX = (center - indicatorWidth / 2).toPx() }
                .width(indicatorWidth)
                .height(3.dp)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun LibraryPage(
    pageItems: List<LibraryItem>,
    allItems: List<LibraryItem>,
    showBreadcrumbs: Boolean,
    currentFolderId: String?,
    onNavigateFolder: (String?) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onItemClick: (LibraryItem) -> Unit,
    onRename: (LibraryItem) -> Unit,
    onMove: (LibraryItem) -> Unit,
    onExportBank: (LibraryItem) -> Unit,
    onShareBank: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    allowMove: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        if (showBreadcrumbs) {
            FolderBreadcrumbs(
                items = allItems,
                currentFolderId = currentFolderId,
                onNavigate = onNavigateFolder,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (pageItems.isEmpty()) {
            EmptyLibrary(modifier = Modifier.weight(1f), onImport = onImport, onExport = onExport)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(pageItems, key = { _, item -> item.id }) { index, item ->
                    AnimatedLibraryRow(
                        item = item,
                        index = index,
                        childCount = allItems.count { it.parentId == item.id },
                        onClick = { onItemClick(item) },
                        onRename = { onRename(item) },
                        onMove = { onMove(item) },
                        onExport = { onExportBank(item) },
                        onShare = { onShareBank(item) },
                        onDelete = { onDelete(item) },
                        allowMove = allowMove,
                    )
                }
            }
        }
    }
}

@Composable
internal fun FolderBreadcrumbs(
    items: List<LibraryItem>,
    currentFolderId: String?,
    onNavigate: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val path = remember(items, currentFolderId) { buildFolderPath(items, currentFolderId) }
    val scrollState = rememberScrollState()
    LaunchedEffect(path, scrollState.maxValue) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(scrollState).padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "/",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        BreadcrumbPart(label = "根目录", onClick = { onNavigate(null) })
        path.forEach { folder ->
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BreadcrumbPart(label = folder.name, onClick = { onNavigate(folder.id) })
        }
    }
}

@Composable
private fun BreadcrumbPart(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 7.dp),
        maxLines = 1,
        style = MaterialTheme.typography.labelLarge,
    )
}

private fun buildFolderPath(items: List<LibraryItem>, currentFolderId: String?): List<LibraryItem> {
    val folders = items.filter { it.type == LibraryItemType.FOLDER }.associateBy { it.id }
    val reversed = mutableListOf<LibraryItem>()
    val visited = mutableSetOf<String>()
    var id = currentFolderId
    while (id != null && visited.add(id)) {
        val folder = folders[id] ?: break
        reversed += folder
        id = folder.parentId
    }
    return reversed.asReversed()
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onImport: () -> Unit, onExport: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("暂无题库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(22.dp))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.UploadFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("导入 Excel")
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("导出标准模板")
        }
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun AnimatedLibraryRow(
    item: LibraryItem,
    index: Int,
    childCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    allowMove: Boolean,
) {
    var visible by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) { delay(index.coerceAtMost(8) * 42L); visible = true }
    AnimatedVisibility(visible, enter = fadeIn(tween(300)) + expandVertically(tween(300))) {
        LibraryRow(item, childCount, onClick, onRename, onMove, onExport, onShare, onDelete, allowMove)
    }
}

@Composable
private fun LibraryRow(
    item: LibraryItem,
    childCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    allowMove: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isFolder = item.type == LibraryItemType.FOLDER
    ListItem(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                if (isFolder) "$childCount 项" else "${item.questionCount} 题 · ${formatDate(item.activityAt)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                if (isFolder) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多") }
                DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    if (allowMove) {
                        DropdownMenuItem(
                            text = { Text("移动") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null) },
                            onClick = { menuExpanded = false; onMove() },
                        )
                    }
                    if (!isFolder) {
                        DropdownMenuItem(
                            text = { Text("导出题库") },
                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            onClick = { menuExpanded = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text("分享题库") },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            onClick = { menuExpanded = false; onShare() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        },
    )
}

@Composable
private fun SpeedDialOption(
    visible: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(110)) + slideOutVertically(tween(150)) { it / 3 },
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
            SmallFloatingActionButton(onClick = onClick, shape = CircleShape) { icon() }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onConfirm(value.trim()) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun android.content.ContentResolver.queryDisplayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "squiz-题库" }
