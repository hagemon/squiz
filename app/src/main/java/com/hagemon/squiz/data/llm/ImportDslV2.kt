package com.hagemon.squiz.data.llm

import com.hagemon.squiz.data.excel.OptionTextParser
import com.hagemon.squiz.model.ParsedQuestion
import com.hagemon.squiz.model.QuestionOption
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.SheetData
import com.hagemon.squiz.model.WorkbookData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ImportScriptV2(
    val version: Int = 2,
    val sheets: List<SheetScriptV2>,
)

data class SheetScriptV2(
    val sheetName: String,
    val headerRowIndex: Int,
    val rowRules: List<RowRuleV2>,
)

data class RowRuleV2(
    val name: String,
    val predicates: List<RowPredicateV2>,
    val type: QuestionType,
    val content: ValueSpecV2,
    val answer: ValueSpecV2,
    val explanation: ValueSpecV2?,
    val options: OptionsSpecV2?,
)

enum class PredicateOperatorV2 {
    EQUALS,
    IN,
    CONTAINS,
    NOT_BLANK,
    IS_BLANK,
}

data class RowPredicateV2(
    val column: Int,
    val operator: PredicateOperatorV2,
    val values: List<String> = emptyList(),
)

enum class ValueModeV2 {
    FIRST_NON_BLANK,
    JOIN,
}

data class ValueSpecV2(
    val columns: List<Int> = emptyList(),
    val constant: String? = null,
    val mode: ValueModeV2 = ValueModeV2.FIRST_NON_BLANK,
    val separator: String = " ",
    val operations: List<TextOperationV2> = emptyList(),
)

sealed interface TextOperationV2 {
    data object Trim : TextOperationV2
    data object CollapseWhitespace : TextOperationV2
    data object Uppercase : TextOperationV2
    data class Replace(val from: String, val to: String, val ignoreCase: Boolean) : TextOperationV2
    data class ValueMap(
        val values: Map<String, String>,
        val ignoreCase: Boolean,
        val keepOriginal: Boolean,
    ) : TextOperationV2
}

enum class OptionsModeV2 {
    COLUMNS,
    SPLIT_CELL,
}

enum class SplitStyleV2 {
    AUTO,
    LINE_BREAK,
    PIPE,
    SEMICOLON,
    COMMA,
    LABELS,
}

data class OptionItemV2(
    val label: String,
    val value: ValueSpecV2,
)

data class OptionsSpecV2(
    val mode: OptionsModeV2,
    val items: List<OptionItemV2> = emptyList(),
    val source: ValueSpecV2? = null,
    val splitStyle: SplitStyleV2 = SplitStyleV2.AUTO,
    val delimiter: String? = null,
)

data class ImportDiagnostic(
    val sheetName: String,
    val sourceRow: Int,
    val message: String,
)

data class ImportExecutionResult(
    val questions: List<ParsedQuestion>,
    val totalDataRows: Int,
    val blankRows: Int,
    val unmatchedRows: Int,
    val invalidRows: Int,
    val diagnostics: List<ImportDiagnostic>,
) {
    val convertedRows: Int get() = questions.size
}

object WorkbookProfiler {
    fun profile(sheet: SheetData): JSONObject {
        val candidateHeaderRows = JSONArray()
        sheet.rows.take(MAX_HEADER_ROWS).forEachIndexed { index, row ->
            candidateHeaderRows.put(
                JSONObject()
                    .put("rowIndex", index)
                    .put("cells", row.toJsonCells()),
            )
        }
        val guessedHeader = guessHeaderRow(sheet)
        val maxColumns = sheet.rows.maxOfOrNull(List<String>::size).orEmptyColumns()
            .coerceAtMost(MAX_COLUMNS)
        val columnProfiles = JSONArray()
        for (column in 0 until maxColumns) {
            val frequencies = linkedMapOf<String, Int>()
            val nonBlankValues = mutableListOf<String>()
            sheet.rows.drop((guessedHeader + 1).coerceAtMost(sheet.rows.size)).forEach { row ->
                val value = row.getOrNull(column).orEmpty().trim()
                if (value.isNotBlank()) {
                    nonBlankValues += value
                    if (frequencies.size < MAX_DISTINCT_TRACKED || value in frequencies) {
                        frequencies[value] = (frequencies[value] ?: 0) + 1
                    }
                }
            }
            val commonValues = JSONArray()
            frequencies.entries.sortedByDescending { it.value }.take(MAX_COMMON_VALUES).forEach { (value, count) ->
                commonValues.put(JSONObject().put("value", value.take(MAX_CELL_CHARS)).put("count", count))
            }
            val delimiterCandidates = JSONArray().apply {
                OptionTextParser.detectDelimiters(nonBlankValues).forEach { candidate ->
                    put(
                        JSONObject()
                            .put("delimiter", candidate.delimiter)
                            .put("matchedRows", candidate.matchedRows)
                            .put("totalRows", candidate.totalRows)
                            .put("commonOptionCount", candidate.commonOptionCount)
                            .put("confidence", candidate.confidence),
                    )
                }
            }
            columnProfiles.put(
                JSONObject()
                    .put("column", column)
                    .put("guessedHeader", sheet.rows.getOrNull(guessedHeader)?.getOrNull(column).orEmpty().take(MAX_CELL_CHARS))
                    .put("nonBlankCount", nonBlankValues.size)
                    .put("commonValues", commonValues)
                    .put("delimiterCandidates", delimiterCandidates)
                    .put("labeledOptionRows", nonBlankValues.count(OptionTextParser::hasLabeledOptions)),
            )
        }
        return JSONObject()
            .put("sheetName", sheet.name)
            .put("rowCount", sheet.rows.size)
            .put("maxColumnCount", maxColumns)
            .put("guessedHeaderRowIndex", guessedHeader)
            .put("candidateHeaderRows", candidateHeaderRows)
            .put("columnProfiles", columnProfiles)
            .put("representativeRows", representativeRows(sheet, guessedHeader))
    }

    private fun representativeRows(sheet: SheetData, headerRow: Int): JSONArray {
        val selected = linkedMapOf<Int, List<String>>()
        val dataRows = sheet.rows.drop(headerRow + 1)
        dataRows.take(FIRST_ROWS).forEachIndexed { offset, row -> selected[headerRow + 1 + offset] = row }

        val header = sheet.rows.getOrNull(headerRow).orEmpty()
        val typeColumn = header.indexOfFirst { raw ->
            val value = raw.lowercase().replace(Regex("[\\s_：:（）()【】\\[\\].-]"), "")
            value.contains("题型") || value == "类型" || value.contains("questiontype")
        }
        if (typeColumn >= 0) {
            val seenTypes = mutableSetOf<String>()
            dataRows.forEachIndexed { offset, row ->
                val value = row.getOrNull(typeColumn).orEmpty().trim()
                if (value.isNotBlank() && seenTypes.size < MAX_TYPE_SAMPLES && seenTypes.add(value)) {
                    selected[headerRow + 1 + offset] = row
                }
            }
        }

        val seenShapes = mutableSetOf<String>()
        dataRows.forEachIndexed { offset, row ->
            val shape = row.take(MAX_COLUMNS).mapIndexedNotNull { index, value -> index.takeIf { value.isNotBlank() } }
                .joinToString(",")
            if (shape.isNotBlank() && seenShapes.size < MAX_SHAPE_SAMPLES && seenShapes.add(shape)) {
                selected[headerRow + 1 + offset] = row
            }
        }

        return JSONArray().apply {
            selected.entries.sortedBy { it.key }.take(MAX_REPRESENTATIVE_ROWS).forEach { (rowIndex, row) ->
                put(JSONObject().put("rowIndex", rowIndex).put("cells", row.toJsonCells()))
            }
        }
    }

    private fun guessHeaderRow(sheet: SheetData): Int = sheet.rows.take(MAX_HEADER_ROWS)
        .mapIndexed { index, row ->
            val textCells = row.count { it.isNotBlank() && it.toDoubleOrNull() == null }
            val nextNonBlank = sheet.rows.getOrNull(index + 1)?.count(String::isNotBlank) ?: 0
            index to (textCells * 3 + minOf(row.size, nextNonBlank))
        }
        .maxByOrNull { it.second }
        ?.first ?: 0

    private fun List<String>.toJsonCells(): JSONArray = JSONArray().apply {
        take(MAX_COLUMNS).forEach { put(it.take(MAX_CELL_CHARS)) }
    }

    private fun Int?.orEmptyColumns(): Int = this ?: 0

    private const val MAX_HEADER_ROWS = 12
    private const val MAX_COLUMNS = 32
    private const val MAX_CELL_CHARS = 180
    private const val MAX_DISTINCT_TRACKED = 80
    private const val MAX_COMMON_VALUES = 8
    private const val FIRST_ROWS = 5
    private const val MAX_TYPE_SAMPLES = 12
    private const val MAX_SHAPE_SAMPLES = 8
    private const val MAX_REPRESENTATIVE_ROWS = 24
}

object ImportDslV2Parser {
    fun parse(raw: String, sourceSheet: SheetData): SheetScriptV2 {
        val jsonText = raw.substring(
            raw.indexOf('{').takeIf { it >= 0 } ?: error("模型未返回 JSON"),
            (raw.lastIndexOf('}').takeIf { it >= 0 } ?: error("模型返回的 JSON 不完整")) + 1,
        )
        val root = JSONObject(jsonText)
        require(root.optInt("version", -1) == 2) { "不支持的智能解析脚本版本" }
        val sheet = root.getJSONObject("sheet")
        require(sheet.getString("sheetName") == sourceSheet.name) { "转换脚本与当前 Sheet 不匹配" }
        val headerRowIndex = sheet.getInt("headerRowIndex")
        require(headerRowIndex in 0..minOf(sourceSheet.rows.lastIndex, 11)) { "表头行超出已分析范围" }
        val columnCount = sourceSheet.rows.maxOfOrNull(List<String>::size) ?: 0
        val rulesJson = sheet.getJSONArray("rowRules")
        require(rulesJson.length() in 1..MAX_RULES) { "每个 Sheet 需要 1 到 $MAX_RULES 条行规则" }
        val rules = (0 until rulesJson.length()).map { index ->
            parseRule(rulesJson.getJSONObject(index), columnCount, index)
        }
        return SheetScriptV2(sourceSheet.name, headerRowIndex, rules)
    }

    private fun parseRule(json: JSONObject, columnCount: Int, index: Int): RowRuleV2 {
        val name = json.optString("name").ifBlank { "规则 ${index + 1}" }.take(80)
        val predicatesJson = json.optJSONArray("when") ?: JSONArray()
        require(predicatesJson.length() <= MAX_PREDICATES) { "$name 的匹配条件过多" }
        val predicates = (0 until predicatesJson.length()).map { predicateIndex ->
            val item = predicatesJson.getJSONObject(predicateIndex)
            val column = item.getInt("column")
            require(column in 0 until columnCount) { "$name 引用了不存在的条件列 $column" }
            val operator = enumValue<PredicateOperatorV2>(item.getString("operator"), "条件操作")
            val valuesJson = item.optJSONArray("values") ?: JSONArray()
            val values = (0 until valuesJson.length()).map { valuesJson.getString(it).take(MAX_LITERAL_CHARS) }
            if (operator in setOf(PredicateOperatorV2.EQUALS, PredicateOperatorV2.IN, PredicateOperatorV2.CONTAINS)) {
                require(values.isNotEmpty()) { "$name 的 $operator 条件需要 values" }
            }
            RowPredicateV2(column, operator, values)
        }
        val type = enumValue<QuestionType>(json.getString("type"), "题型")
        require(type != QuestionType.UNKNOWN) { "V2 的每条行规则必须生成明确题型" }
        val fields = json.getJSONObject("fields")
        return RowRuleV2(
            name = name,
            predicates = predicates,
            type = type,
            content = parseValue(fields.getJSONObject("content"), columnCount, "$name.content"),
            answer = parseValue(fields.getJSONObject("answer"), columnCount, "$name.answer"),
            explanation = fields.optJSONObject("explanation")?.let {
                parseValue(it, columnCount, "$name.explanation")
            },
            options = fields.optJSONObject("options")?.let { parseOptions(it, columnCount, name) },
        )
    }

    private fun parseOptions(json: JSONObject, columnCount: Int, ruleName: String): OptionsSpecV2 {
        val mode = enumValue<OptionsModeV2>(json.getString("mode"), "选项模式")
        return when (mode) {
            OptionsModeV2.COLUMNS -> {
                val itemsJson = json.getJSONArray("items")
                require(itemsJson.length() in 1..26) { "$ruleName 的选项数量必须在 1 到 26 之间" }
                val items = (0 until itemsJson.length()).map { index ->
                    val item = itemsJson.getJSONObject(index)
                    val label = item.getString("label").trim().uppercase()
                    require(label.matches(Regex("[A-Z]"))) { "$ruleName 返回了无效选项标签 $label" }
                    OptionItemV2(label, parseValue(item.getJSONObject("value"), columnCount, "$ruleName.option.$label"))
                }
                require(items.map { it.label }.distinct().size == items.size) { "$ruleName 存在重复选项标签" }
                OptionsSpecV2(mode = mode, items = items)
            }
            OptionsModeV2.SPLIT_CELL -> {
                val delimiter = json.optString("delimiter").takeIf { json.has("delimiter") }
                require(delimiter == null || delimiter.isNotEmpty()) { "$ruleName 的分隔符不能为空" }
                require(delimiter == null || delimiter.length <= MAX_SEPARATOR_CHARS) { "$ruleName 的分隔符过长" }
                OptionsSpecV2(
                    mode = mode,
                    source = parseValue(json.getJSONObject("source"), columnCount, "$ruleName.options.source"),
                    splitStyle = enumValue<SplitStyleV2>(json.optString("splitStyle", "AUTO"), "拆分方式"),
                    delimiter = delimiter,
                )
            }
        }
    }

    private fun parseValue(json: JSONObject, columnCount: Int, path: String): ValueSpecV2 {
        val columnsJson = json.optJSONArray("columns") ?: JSONArray()
        require(columnsJson.length() <= MAX_SOURCE_COLUMNS) { "$path 引用的源列过多" }
        val columns = (0 until columnsJson.length()).map { index ->
            columnsJson.getInt(index).also { require(it in 0 until columnCount) { "$path 引用了不存在的列 $it" } }
        }
        val constant = json.optString("constant").takeIf { json.has("constant") }?.take(MAX_LITERAL_CHARS)
        require(columns.isNotEmpty() || constant != null) { "$path 必须包含 columns 或 constant" }
        val mode = enumValue<ValueModeV2>(json.optString("mode", "FIRST_NON_BLANK"), "取值模式")
        val separator = json.optString("separator", " ").take(MAX_SEPARATOR_CHARS)
        val operationsJson = json.optJSONArray("operations") ?: JSONArray()
        require(operationsJson.length() <= MAX_OPERATIONS) { "$path 的转换操作过多" }
        val operations = (0 until operationsJson.length()).map { index ->
            parseOperation(operationsJson.getJSONObject(index), path)
        }
        return ValueSpecV2(columns, constant, mode, separator, operations)
    }

    private fun parseOperation(json: JSONObject, path: String): TextOperationV2 = when (json.getString("op").uppercase()) {
        "TRIM" -> TextOperationV2.Trim
        "COLLAPSE_WHITESPACE" -> TextOperationV2.CollapseWhitespace
        "UPPERCASE" -> TextOperationV2.Uppercase
        "REPLACE" -> TextOperationV2.Replace(
            from = json.getString("from").take(MAX_LITERAL_CHARS).also { require(it.isNotEmpty()) { "$path 的替换源不能为空" } },
            to = json.optString("to").take(MAX_LITERAL_CHARS),
            ignoreCase = json.optBoolean("ignoreCase", false),
        )
        "VALUE_MAP" -> {
            val valuesJson = json.getJSONObject("values")
            require(valuesJson.length() in 1..MAX_VALUE_MAP) { "$path 的值映射数量超出限制" }
            val values = buildMap {
                valuesJson.keys().forEach { key -> put(key.take(MAX_LITERAL_CHARS), valuesJson.getString(key).take(MAX_LITERAL_CHARS)) }
            }
            TextOperationV2.ValueMap(
                values = values,
                ignoreCase = json.optBoolean("ignoreCase", true),
                keepOriginal = json.optBoolean("keepOriginal", true),
            )
        }
        else -> error("$path 包含不支持的转换操作：${json.getString("op")}")
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, label: String): T =
        enumValues<T>().firstOrNull { it.name == raw.trim().uppercase() }
            ?: error("转换脚本返回了不支持的$label：$raw")

    private const val MAX_RULES = 20
    private const val MAX_PREDICATES = 6
    private const val MAX_SOURCE_COLUMNS = 8
    private const val MAX_OPERATIONS = 8
    private const val MAX_VALUE_MAP = 50
    private const val MAX_LITERAL_CHARS = 180
    private const val MAX_SEPARATOR_CHARS = 12
}

object ImportDslV2Executor {
    fun execute(workbook: WorkbookData, script: ImportScriptV2): ImportExecutionResult =
        executeInternal(workbook, script) { _, _ -> }

    suspend fun executeWithProgress(
        workbook: WorkbookData,
        script: ImportScriptV2,
        onProgress: (processedRows: Int, totalRows: Int) -> Unit,
    ): ImportExecutionResult = withContext(Dispatchers.Default) {
        executeInternal(workbook, script, onProgress)
    }

    private fun executeInternal(
        workbook: WorkbookData,
        script: ImportScriptV2,
        onProgress: (processedRows: Int, totalRows: Int) -> Unit,
    ): ImportExecutionResult {
        require(script.version == 2) { "不支持的转换脚本版本" }
        val nonEmptySheets = workbook.sheets.filter { sheet -> sheet.rows.any { row -> row.any(String::isNotBlank) } }
        require(script.sheets.map { it.sheetName }.toSet() == nonEmptySheets.map { it.name }.toSet()) {
            "转换脚本与当前工作簿不匹配"
        }
        val questions = mutableListOf<ParsedQuestion>()
        val diagnostics = mutableListOf<ImportDiagnostic>()
        var totalRows = 0
        var blankRows = 0
        var unmatchedRows = 0
        var invalidRows = 0
        val scripts = script.sheets.associateBy { it.sheetName }
        val expectedRows = nonEmptySheets.sumOf { sheet ->
            val headerRowIndex = scripts.getValue(sheet.name).headerRowIndex
            (sheet.rows.size - headerRowIndex - 1).coerceAtLeast(0)
        }
        val progressStep = maxOf(1, expectedRows / 100)
        onProgress(0, expectedRows)
        nonEmptySheets.forEach { sheet ->
            val sheetScript = scripts.getValue(sheet.name)
            sheet.rows.drop(sheetScript.headerRowIndex + 1).forEachIndexed { offset, row ->
                totalRows++
                val sourceRow = sheetScript.headerRowIndex + offset + 2
                if (row.all(String::isBlank)) {
                    blankRows++
                } else {
                    val rule = sheetScript.rowRules.firstOrNull { it.matches(row) }
                    if (rule == null) {
                        unmatchedRows++
                        diagnostics.addLimited(ImportDiagnostic(sheet.name, sourceRow, "没有匹配任何行规则"))
                    } else {
                        runCatching { rule.convert(row, sheet.name, sourceRow) }
                            .onSuccess(questions::add)
                            .onFailure { error ->
                                invalidRows++
                                diagnostics.addLimited(
                                    ImportDiagnostic(sheet.name, sourceRow, "${rule.name}：${error.message ?: "转换失败"}"),
                                )
                            }
                    }
                }
                if (totalRows == expectedRows || totalRows % progressStep == 0) {
                    onProgress(totalRows, expectedRows)
                }
            }
        }
        val sheetOrder = nonEmptySheets.mapIndexed { index, sheet -> sheet.name to index }.toMap()
        val sortedQuestions = questions.sortedWith(
            compareBy<ParsedQuestion> { it.type.conversionOrder }
                .thenBy { sheetOrder[it.sheetName] ?: Int.MAX_VALUE }
                .thenBy(ParsedQuestion::sourceRow),
        )
        return ImportExecutionResult(sortedQuestions, totalRows, blankRows, unmatchedRows, invalidRows, diagnostics)
    }

    private val QuestionType.conversionOrder: Int
        get() = when (this) {
            QuestionType.SINGLE_CHOICE -> 0
            QuestionType.MULTIPLE_CHOICE -> 1
            QuestionType.TRUE_FALSE -> 2
            QuestionType.CALCULATION -> 3
            QuestionType.SHORT_ANSWER -> 4
            QuestionType.UNKNOWN -> 5
        }

    private fun RowRuleV2.matches(row: List<String>): Boolean = predicates.all { predicate ->
        val actual = row.getOrNull(predicate.column).orEmpty().trim()
        when (predicate.operator) {
            PredicateOperatorV2.EQUALS -> predicate.values.firstOrNull()?.equals(actual, ignoreCase = true) == true
            PredicateOperatorV2.IN -> predicate.values.any { it.equals(actual, ignoreCase = true) }
            PredicateOperatorV2.CONTAINS -> predicate.values.any { actual.contains(it, ignoreCase = true) }
            PredicateOperatorV2.NOT_BLANK -> actual.isNotBlank()
            PredicateOperatorV2.IS_BLANK -> actual.isBlank()
        }
    }

    private fun RowRuleV2.convert(row: List<String>, sheetName: String, sourceRow: Int): ParsedQuestion {
        val prompt = content.evaluate(row).trim()
        require(prompt.isNotBlank()) { "题目内容为空" }
        val rawAnswer = answer.evaluate(row).trim()
        require(rawAnswer.isNotBlank()) { "答案为空" }
        val optionValues = options?.evaluate(row).orEmpty()
        if (type in setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE)) {
            require(optionValues.size >= 2) { "选择题至少需要两个非空选项" }
        }
        val correctLabels = normalizeAnswer(type, rawAnswer, optionValues)
        if (type in setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE)) {
            require(correctLabels.isNotEmpty()) { "无法把答案“${rawAnswer.take(40)}”对应到选项" }
        }
        val finalOptions = when {
            type == QuestionType.TRUE_FALSE && optionValues.isEmpty() -> listOf("A" to "对", "B" to "错")
            else -> optionValues
        }.map { (label, value) -> QuestionOption(label, value, label in correctLabels) }
        val normalizedAnswer = when (type) {
            QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE ->
                correctLabels.sorted().joinToString("")
            else -> rawAnswer
        }
        return ParsedQuestion(
            type = type,
            prompt = prompt,
            options = finalOptions,
            answer = normalizedAnswer,
            explanation = explanation?.evaluate(row)?.trim()?.takeIf(String::isNotBlank),
            sheetName = sheetName,
            sourceRow = sourceRow,
        )
    }

    private fun ValueSpecV2.evaluate(row: List<String>): String {
        var value = constant ?: when (mode) {
            ValueModeV2.FIRST_NON_BLANK -> columns.firstNotNullOfOrNull { column ->
                row.getOrNull(column)?.takeIf(String::isNotBlank)
            }.orEmpty()
            ValueModeV2.JOIN -> columns.map { row.getOrNull(it).orEmpty().trim() }
                .filter(String::isNotBlank)
                .joinToString(separator)
        }
        operations.forEach { operation ->
            value = when (operation) {
                TextOperationV2.Trim -> value.trim()
                TextOperationV2.CollapseWhitespace -> value.trim().replace(Regex("\\s+"), " ")
                TextOperationV2.Uppercase -> value.uppercase()
                is TextOperationV2.Replace -> value.replace(operation.from, operation.to, operation.ignoreCase)
                is TextOperationV2.ValueMap -> {
                    val match = operation.values.entries.firstOrNull { (key, _) ->
                        key.equals(value.trim(), ignoreCase = operation.ignoreCase)
                    }
                    match?.value ?: if (operation.keepOriginal) value else ""
                }
            }
        }
        return value
    }

    private fun OptionsSpecV2.evaluate(row: List<String>): List<Pair<String, String>> = when (mode) {
        OptionsModeV2.COLUMNS -> items.mapNotNull { item ->
            item.value.evaluate(row).trim().takeIf(String::isNotBlank)?.let { item.label to it }
        }
        OptionsModeV2.SPLIT_CELL -> splitOptions(source?.evaluate(row).orEmpty(), splitStyle, delimiter)
    }

    private fun splitOptions(raw: String, style: SplitStyleV2, delimiter: String?): List<Pair<String, String>> = when {
        delimiter != null -> OptionTextParser.split(raw, delimiter)
        style == SplitStyleV2.LABELS -> OptionTextParser.splitByLabels(raw)
        style == SplitStyleV2.LINE_BREAK -> OptionTextParser.split(raw, "\n")
        style == SplitStyleV2.PIPE -> OptionTextParser.split(raw, "|")
        style == SplitStyleV2.SEMICOLON -> OptionTextParser.split(raw, if ('；' in raw) "；" else ";")
        style == SplitStyleV2.COMMA -> OptionTextParser.split(raw, if ('，' in raw) "，" else ",")
        else -> {
            OptionTextParser.splitByLabels(raw).takeIf { it.size >= 2 }
                ?: OptionTextParser.detectDelimiters(listOf(raw)).firstOrNull()?.let { candidate ->
                    OptionTextParser.split(raw, candidate.delimiter)
                }.orEmpty()
        }
    }

    private fun normalizeAnswer(
        type: QuestionType,
        raw: String,
        options: List<Pair<String, String>>,
    ): Set<String> {
        val normalized = raw.trim()
        options.firstOrNull { (_, text) -> text.equals(normalized, ignoreCase = true) }
            ?.let { return setOf(it.first) }
        if (type == QuestionType.TRUE_FALSE) {
            when (normalized.lowercase()) {
                "正确", "对", "是", "√", "true", "1", "yes" -> return setOf("A")
                "错误", "错", "否", "×", "x", "false", "0", "no" -> return setOf("B")
            }
        }
        val compact = normalized.replace(Regex("[\\s,，;；、/|]+"), "").uppercase()
        if (compact.matches(Regex("[A-Z]+"))) return compact.map(Char::toString).toSet()
        return emptySet()
    }

    private fun MutableList<ImportDiagnostic>.addLimited(value: ImportDiagnostic) {
        if (size < MAX_DIAGNOSTICS) add(value)
    }

    private const val MAX_DIAGNOSTICS = 30
}
