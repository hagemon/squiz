package com.hagemon.squiz.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hagemon.squiz.data.excel.ExcelAnalyzer
import com.hagemon.squiz.data.excel.OptionTextParser
import com.hagemon.squiz.model.ColumnField
import com.hagemon.squiz.model.ImportAnalysis
import com.hagemon.squiz.model.OptionsSplitMapping
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.SheetMapping

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportMappingScreen(
    analysis: ImportAnalysis,
    mappings: List<SheetMapping>,
    onMappingsChange: (List<SheetMapping>) -> Unit,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    llmConfigured: Boolean,
    smartParseStatus: String?,
    smartParseProgress: Float?,
    onSmartParse: () -> Unit,
) {
    val isSmartParsing = smartParseStatus != null
    var selectedSheetIndex by remember { mutableIntStateOf(0) }
    var splitColumnIndex by remember { mutableStateOf<Int?>(null) }
    var splitDelimiterInput by remember { mutableStateOf("") }
    val sheetIndex = selectedSheetIndex.coerceIn(0, (analysis.workbook.sheets.lastIndex).coerceAtLeast(0))
    val sheet = analysis.workbook.sheets.getOrNull(sheetIndex)
    val mapping = sheet?.let { selected -> mappings.firstOrNull { it.sheetName == selected.name } }

    fun updateMapping(updated: SheetMapping) {
        onMappingsChange(mappings.map { if (it.sheetName == updated.sheetName) updated else it })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("匹配 Excel 字段", fontWeight = FontWeight.SemiBold) },
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
                    onClick = onPreview,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    enabled = mappings.all { ColumnField.CONTENT in it.columns && ColumnField.ANSWER in it.columns },
                ) {
                    Icon(Icons.Rounded.RemoveRedEye, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("预览")
                }
            }
        },
    ) { innerPadding ->
        if (sheet == null || mapping == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(
                        onClick = onSmartParse,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = llmConfigured && !isSmartParsing,
                    ) {
                        if (isSmartParsing) {
                            if (smartParseProgress == null) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                CircularProgressIndicator(
                                    progress = { smartParseProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(smartParseStatus ?: "智能解析")
                    }
                    Text(
                        if (llmConfigured) {
                            "完整文件只在本机扫描；每个 Sheet 仅发送结构统计和最多 24 行代表样例。"
                        } else {
                            "请先在“设置 → 大模型接口”中完成配置。"
                        },
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    analysis.workbook.sheets.forEachIndexed { index, item ->
                        FilterChip(
                            selected = sheetIndex == index,
                            onClick = {
                                splitColumnIndex = null
                                selectedSheetIndex = index
                            },
                            label = { Text(item.name, maxLines = 1) },
                        )
                    }
                }
            }
            item {
                Text(
                    "请选择表头所在行，并确认每一列对应的字段。无法识别的列可以保留为“不导入”。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text("表头行") },
                    supportingContent = { Text("当前为第 ${mapping.headerRowIndex + 1} 行") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val row = (mapping.headerRowIndex - 1).coerceAtLeast(0)
                                    updateMapping(ExcelAnalyzer.suggestMapping(sheet, row))
                                },
                                enabled = mapping.headerRowIndex > 0,
                            ) { Text("−") }
                            Text("${mapping.headerRowIndex + 1}", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = {
                                    val row = (mapping.headerRowIndex + 1).coerceAtMost(minOf(sheet.rows.lastIndex, 19))
                                    updateMapping(ExcelAnalyzer.suggestMapping(sheet, row))
                                },
                                enabled = mapping.headerRowIndex < minOf(sheet.rows.lastIndex, 19),
                            ) { Text("+") }
                        }
                    },
                )
            }
            item {
                MappingDropdownRow(
                    title = "此 Sheet 的题型",
                    value = mapping.fixedType.displayName,
                    options = QuestionType.entries,
                    optionLabel = QuestionType::displayName,
                    onSelected = { updateMapping(mapping.copy(fixedType = it)) },
                )
            }
            item {
                Text(
                    "列对应关系",
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val headers = sheet.rows.getOrElse(mapping.headerRowIndex) { emptyList() }
            itemsIndexed(headers) { columnIndex, header ->
                val currentSplit = mapping.optionsSplit?.takeIf { it.columnIndex == columnIndex }
                MappingDropdownRow(
                    title = header.ifBlank { "第 ${columnIndex + 1} 列" },
                    value = currentSplit?.let { "拆分选项 · ${it.delimiter.visibleDelimiter()}" }
                        ?: mapping.columns.getOrElse(columnIndex) { ColumnField.NONE }.displayName,
                    options = ColumnField.entries,
                    optionLabel = ColumnField::displayName,
                    onSelected = { selected ->
                        val size = maxOf(headers.size, mapping.columns.size)
                        val newColumns = List(size) { index ->
                            val old = mapping.columns.getOrElse(index) { ColumnField.NONE }
                            when {
                                index == columnIndex -> selected
                                selected != ColumnField.NONE && old == selected -> ColumnField.NONE
                                else -> old
                            }
                        }
                        updateMapping(
                            mapping.copy(
                                columns = newColumns,
                                optionsSplit = mapping.optionsSplit?.takeUnless { it.columnIndex == columnIndex },
                            ),
                        )
                    },
                    specialActionLabel = "拆分为多个选项…",
                    onSpecialAction = {
                        val values = sheet.rows.drop(mapping.headerRowIndex + 1)
                            .map { row -> row.getOrNull(columnIndex).orEmpty() }
                        val suggested = currentSplit?.delimiter
                            ?: OptionTextParser.detectDelimiters(values).firstOrNull()?.delimiter
                            ?: ""
                        splitColumnIndex = columnIndex
                        splitDelimiterInput = suggested.toDelimiterInput()
                    },
                )
            }
        }
    }

    val activeColumn = splitColumnIndex
    if (sheet != null && mapping != null && activeColumn != null) {
        val columnTitle = sheet.rows.getOrNull(mapping.headerRowIndex)?.getOrNull(activeColumn)
            ?.takeIf(String::isNotBlank) ?: "第 ${activeColumn + 1} 列"
        val example = sheet.rows.drop(mapping.headerRowIndex + 1)
            .firstNotNullOfOrNull { row -> row.getOrNull(activeColumn)?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()
        val delimiter = splitDelimiterInput.decodeDelimiter()
        val preview = OptionTextParser.split(example, delimiter)
        AlertDialog(
            onDismissRequest = { splitColumnIndex = null },
            icon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
            title = { Text("拆分为多个选项") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "将“$columnTitle”这一列按固定分隔符拆分，每行可以得到不同数量的选项。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("数据示例", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            example.ifBlank { "该列暂无可用示例" },
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        delimiterPresets.forEach { (label, value) ->
                            FilterChip(
                                selected = splitDelimiterInput == value,
                                onClick = { splitDelimiterInput = value },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = splitDelimiterInput,
                        onValueChange = { value -> if (value.length <= 12) splitDelimiterInput = value },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("分隔符") },
                        supportingText = { Text("换行请输入 \\n，Tab 请输入 \\t，也可使用多字符分隔符。") },
                    )
                    Text(
                        if (preview.size >= 2) "预览：识别到 ${preview.size} 个选项" else "预览：尚未拆分出多个选项",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (preview.size >= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    preview.take(8).forEach { (label, text) ->
                        Text(
                            "$label. $text",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val size = maxOf(
                            sheet.rows.getOrNull(mapping.headerRowIndex)?.size ?: 0,
                            mapping.columns.size,
                        )
                        val columns = List(size) { index ->
                            if (index == activeColumn) ColumnField.NONE
                            else mapping.columns.getOrElse(index) { ColumnField.NONE }
                        }
                        updateMapping(
                            mapping.copy(
                                columns = columns,
                                optionsSplit = OptionsSplitMapping(activeColumn, delimiter),
                            ),
                        )
                        splitColumnIndex = null
                    },
                    enabled = delimiter.isNotEmpty() && preview.size >= 2,
                ) { Text("确认拆分") }
            },
            dismissButton = {
                TextButton(onClick = { splitColumnIndex = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun <T> MappingDropdownRow(
    title: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    specialActionLabel: String? = null,
    onSpecialAction: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Box(modifier = Modifier.widthIn(min = 124.dp)) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(value, maxLines = 1, softWrap = false)
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.widthIn(min = 196.dp),
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionLabel(option)) },
                            onClick = { expanded = false; onSelected(option) },
                        )
                    }
                    if (specialActionLabel != null && onSpecialAction != null) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.ContentCut,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            text = {
                                Text(
                                    specialActionLabel,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                            onClick = {
                                expanded = false
                                onSpecialAction()
                            },
                        )
                    }
                }
            }
        },
    )
}

private val delimiterPresets = listOf(
    "换行" to "\\n",
    "|" to "|",
    ";" to ";",
    "；" to "；",
    "," to ",",
    "，" to "，",
    "Tab" to "\\t",
)

private fun String.decodeDelimiter(): String = when (this) {
    "\\n" -> "\n"
    "\\t" -> "\t"
    else -> this
}

private fun String.toDelimiterInput(): String = when (this) {
    "\n" -> "\\n"
    "\t" -> "\\t"
    else -> this
}

private fun String.visibleDelimiter(): String = when (this) {
    "\n" -> "换行"
    "\t" -> "Tab"
    else -> "“$this”"
}
