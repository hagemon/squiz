package com.hagemon.squiz.data.excel

import com.hagemon.squiz.model.ColumnField
import com.hagemon.squiz.model.ImportAnalysis
import com.hagemon.squiz.model.ParsedQuestion
import com.hagemon.squiz.model.QuestionOption
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.SheetData
import com.hagemon.squiz.model.SheetMapping
import com.hagemon.squiz.model.WorkbookData
import com.hagemon.squiz.model.optionLabel

object ExcelAnalyzer {
    fun analyze(workbook: WorkbookData): ImportAnalysis {
        val mappings = workbook.sheets.map(::suggestMapping)
        val requiresManual = workbook.sheets.zip(mappings).any { (sheet, mapping) ->
            sheet.rows.drop(mapping.headerRowIndex + 1).any { row -> row.any(String::isNotBlank) } &&
                !mapping.isDirectlyImportable()
        }
        return ImportAnalysis(workbook, mappings, requiresManual)
    }

    fun suggestMapping(sheet: SheetData, forcedHeaderRow: Int? = null): SheetMapping {
        val headerRow = forcedHeaderRow?.coerceIn(0, (sheet.rows.lastIndex).coerceAtLeast(0))
            ?: detectHeaderRow(sheet)
        val headers = sheet.rows.getOrElse(headerRow) { emptyList() }
        return SheetMapping(
            sheetName = sheet.name,
            headerRowIndex = headerRow,
            fixedType = detectQuestionType(sheet.name),
            columns = headers.map(::matchField),
        )
    }

    fun parse(workbook: WorkbookData, mappings: List<SheetMapping>): List<ParsedQuestion> {
        val mappingBySheet = mappings.associateBy { it.sheetName }
        return workbook.sheets.flatMap { sheet ->
            val mapping = mappingBySheet[sheet.name] ?: return@flatMap emptyList()
            if (mapping.skipImport) return@flatMap emptyList()
            parseSheet(sheet, mapping)
        }
    }

    private fun parseSheet(sheet: SheetData, mapping: SheetMapping): List<ParsedQuestion> {
        val contentColumn = mapping.columns.indexOf(ColumnField.CONTENT)
        val answerColumn = mapping.columns.indexOf(ColumnField.ANSWER)
        val typeColumn = mapping.columns.indexOf(ColumnField.TYPE)
        val explanationColumn = mapping.columns.indexOf(ColumnField.EXPLANATION)
        if (contentColumn < 0 || answerColumn < 0) return emptyList()

        return sheet.rows.drop(mapping.headerRowIndex + 1).mapIndexedNotNull { offset, row ->
            val firstNonBlank = row.firstOrNull(String::isNotBlank)?.trim()
            if (firstNonBlank == "$&$" || firstNonBlank == "\$&\$") return@mapIndexedNotNull null
            val prompt = row.getOrNull(contentColumn).orEmpty().trim()
            if (prompt.isBlank()) return@mapIndexedNotNull null
            val type = if (mapping.fixedType == QuestionType.UNKNOWN) {
                parseQuestionType(row.getOrNull(typeColumn).orEmpty())
            } else {
                mapping.fixedType
            }
            if (type == QuestionType.UNKNOWN) return@mapIndexedNotNull null
            val answer = row.getOrNull(answerColumn).orEmpty().trim()
            val mappedOptions = mapping.columns.mapIndexedNotNull { column, field ->
                val label = field.optionLabel ?: return@mapIndexedNotNull null
                val text = row.getOrNull(column).orEmpty().trim()
                if (text.isBlank()) return@mapIndexedNotNull null
                label to text
            }
            val optionValues = mapping.optionsSplit?.let { split ->
                OptionTextParser.split(row.getOrNull(split.columnIndex).orEmpty(), split.delimiter)
            }.orEmpty().ifEmpty { mappedOptions }
            val compactAnswer = answer.uppercase().replace(Regex("[\\s,，;；、/|]+"), "")
            val correctLabels = compactAnswer.takeIf { it.matches(Regex("[A-Z]+")) }
                ?.map(Char::toString)
                ?.toSet()
                .orEmpty()
            var options = optionValues.map { (label, text) ->
                QuestionOption(label, text, label in correctLabels || text.equals(answer, ignoreCase = true))
            }
            if (type == QuestionType.TRUE_FALSE && options.isEmpty()) {
                val normalized = answer.replace("正确", "对").replace("错误", "错").trim()
                options = listOf(
                    QuestionOption("A", "对", normalized == "对" || normalized == "√"),
                    QuestionOption("B", "错", normalized == "错" || normalized == "×" || normalized.equals("x", true)),
                )
            }
            ParsedQuestion(
                type = type,
                prompt = prompt,
                options = options,
                answer = answer,
                explanation = row.getOrNull(explanationColumn)?.trim()?.takeIf(String::isNotBlank),
                sheetName = sheet.name,
                sourceRow = mapping.headerRowIndex + offset + 2,
            )
        }
    }

    private fun detectHeaderRow(sheet: SheetData): Int = sheet.rows
        .take(20)
        .mapIndexed { index, row ->
            val matches = row.map(::matchField).filterNot { it == ColumnField.NONE }
            val score = matches.distinct().size * 10 +
                if (ColumnField.CONTENT in matches) 8 else 0 +
                if (ColumnField.ANSWER in matches) 5 else 0
            index to score
        }
        .maxByOrNull { it.second }
        ?.first ?: 0

    private fun SheetMapping.isDirectlyImportable(): Boolean {
        if (fixedType == QuestionType.UNKNOWN) return false
        if (ColumnField.CONTENT !in columns || ColumnField.ANSWER !in columns) return false
        return when (fixedType) {
            QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE ->
                optionsSplit != null || columns.count { it.optionLabel != null } >= 2
            else -> true
        }
    }

    private fun matchField(raw: String): ColumnField {
        val value = normalize(raw)
        return when {
            value in setOf("题型", "类型", "题目类型", "试题类型") -> ColumnField.TYPE
            value in setOf("题目", "题干", "问题", "题目内容", "试题内容", "内容") -> ColumnField.CONTENT
            value in setOf("答案", "正确答案", "参考答案", "标准答案") -> ColumnField.ANSWER
            value in setOf("解析", "答案解析", "题目解析", "说明") -> ColumnField.EXPLANATION
            Regex("^(选项)?[a-g](选项)?$").matches(value) -> when (value.first { it in 'a'..'g' }) {
                'a' -> ColumnField.OPTION_A
                'b' -> ColumnField.OPTION_B
                'c' -> ColumnField.OPTION_C
                'd' -> ColumnField.OPTION_D
                'e' -> ColumnField.OPTION_E
                'f' -> ColumnField.OPTION_F
                else -> ColumnField.OPTION_G
            }
            else -> ColumnField.NONE
        }
    }

    private fun detectQuestionType(value: String): QuestionType = when {
        "多选" in value -> QuestionType.MULTIPLE_CHOICE
        "单选" in value || "选择题" in value -> QuestionType.SINGLE_CHOICE
        "判断" in value || "是非" in value -> QuestionType.TRUE_FALSE
        "计算" in value -> QuestionType.CALCULATION
        "简答" in value || "问答" in value -> QuestionType.SHORT_ANSWER
        else -> QuestionType.UNKNOWN
    }

    private fun parseQuestionType(value: String): QuestionType = detectQuestionType(value).let { detected ->
        if (detected != QuestionType.UNKNOWN) detected else when (normalize(value)) {
            "single", "singlechoice" -> QuestionType.SINGLE_CHOICE
            "multiple", "multiplechoice" -> QuestionType.MULTIPLE_CHOICE
            "truefalse", "boolean" -> QuestionType.TRUE_FALSE
            "calculation" -> QuestionType.CALCULATION
            "shortanswer" -> QuestionType.SHORT_ANSWER
            else -> QuestionType.UNKNOWN
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\s_：:（）()【】\\[\\].-]"), "")
}
