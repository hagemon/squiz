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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import com.hagemon.squiz.model.ColumnField
import com.hagemon.squiz.model.ImportAnalysis
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
) {
    var selectedSheetIndex by remember { mutableIntStateOf(0) }
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
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    analysis.workbook.sheets.forEachIndexed { index, item ->
                        FilterChip(
                            selected = sheetIndex == index,
                            onClick = { selectedSheetIndex = index },
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
                MappingDropdownRow(
                    title = header.ifBlank { "第 ${columnIndex + 1} 列" },
                    value = mapping.columns.getOrElse(columnIndex) { ColumnField.NONE }.displayName,
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
                        updateMapping(mapping.copy(columns = newColumns))
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> MappingDropdownRow(
    title: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(value)
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionLabel(option)) },
                            onClick = { expanded = false; onSelected(option) },
                        )
                    }
                }
            }
        },
    )
}
