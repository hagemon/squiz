package com.hagemon.squiz.data.excel

import com.hagemon.squiz.model.ColumnField
import com.hagemon.squiz.model.OptionsSplitMapping
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.SheetData
import com.hagemon.squiz.model.SheetMapping
import com.hagemon.squiz.model.WorkbookData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcelAnalyzerTest {
    @Test
    fun manualMappingSplitsOneColumnIntoVariableOptions() {
        val sheet = SheetData(
            "手动拆分",
            listOf(
                listOf("题目", "备选项", "答案"),
                listOf("选择题", "甲||乙||丙||丁", "C"),
            ),
        )
        val mapping = SheetMapping(
            sheetName = sheet.name,
            headerRowIndex = 0,
            fixedType = QuestionType.SINGLE_CHOICE,
            columns = listOf(ColumnField.CONTENT, ColumnField.NONE, ColumnField.ANSWER),
            optionsSplit = OptionsSplitMapping(columnIndex = 1, delimiter = "||"),
        )

        val questions = ExcelAnalyzer.parse(WorkbookData("manual.xlsx", listOf(sheet)), listOf(mapping))

        assertEquals(listOf("A", "B", "C", "D"), questions.single().options.map { it.label })
        assertEquals(listOf("甲", "乙", "丙", "丁"), questions.single().options.map { it.text })
        assertTrue(questions.single().options.single { it.label == "C" }.isCorrect)
    }

    @Test
    fun detectorPrefersStableMultiCharacterDelimiter() {
        val candidates = OptionTextParser.detectDelimiters(
            listOf(
                "A. 甲@#B. 乙@#C. 丙",
                "A. 东@#B. 西@#C. 南",
                "A. 是@#B. 否@#C. 不确定",
            ),
        )

        assertEquals("@#", candidates.first().delimiter)
        assertEquals(3, candidates.first().commonOptionCount)
    }
}
