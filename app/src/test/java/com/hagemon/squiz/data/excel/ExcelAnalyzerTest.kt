package com.hagemon.squiz.data.excel

import com.hagemon.squiz.model.ColumnField
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.QuestionOption
import com.hagemon.squiz.model.QuizQuestion
import com.hagemon.squiz.model.SheetData
import com.hagemon.squiz.model.WorkbookData
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import jxl.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ExcelAnalyzerTest {
    @Test
    fun exportedTemplateContainsThreeImportableExampleQuestions() {
        val bytes = ByteArrayOutputStream().also(StandardTemplateExporter::writeWorkbook).toByteArray()
        System.getenv("SQUIZ_TEMPLATE_OUTPUT")?.let { File(it).writeBytes(bytes) }
        val source = Workbook.getWorkbook(ByteArrayInputStream(bytes))
        val workbook = try {
            WorkbookData(
                "squiz-标准题库模板.xls",
                source.sheets.map { sheet ->
                    SheetData(
                        sheet.name,
                        (0 until sheet.rows).map { row ->
                            (0 until sheet.columns).map { column -> sheet.getCell(column, row).contents.trim() }
                        },
                    )
                },
            )
        } finally {
            source.close()
        }
        val analysis = ExcelAnalyzer.analyze(workbook)
        val questions = ExcelAnalyzer.parse(workbook, analysis.mappings)

        assertEquals(listOf("单选题", "多选题", "判断题"), workbook.sheets.map { it.name })
        assertFalse(analysis.requiresManualMapping)
        assertEquals(3, questions.size)
        assertEquals(1, questions.count { it.type == QuestionType.SINGLE_CHOICE })
        assertEquals(1, questions.count { it.type == QuestionType.MULTIPLE_CHOICE })
        assertEquals(1, questions.count { it.type == QuestionType.TRUE_FALSE })
        assertEquals(0, questions.count { it.type == QuestionType.SHORT_ANSWER })
        assertTrue(questions.all { it.prompt.length >= 45 })
        val judgement = questions.single { it.type == QuestionType.TRUE_FALSE }
        assertEquals(listOf("A", "B"), judgement.options.map { it.label })
        assertEquals("A", judgement.options.single { it.isCorrect }.label)
    }

    @Test
    fun attachedWorkbookIsRecognizedAndParsed() {
        val path = System.getenv("SQUIZ_SAMPLE_XLS")
        assumeTrue("未提供 SQUIZ_SAMPLE_XLS，跳过附件回归测试", !path.isNullOrBlank())
        requireNotNull(path)
        val source = Workbook.getWorkbook(File(path))
        val workbook = try {
            WorkbookData(
                File(path).name,
                source.sheets.map { sheet ->
                    SheetData(
                        sheet.name,
                        (0 until sheet.rows).map { row ->
                            (0 until sheet.columns).map { column -> sheet.getCell(column, row).contents.trim() }
                        },
                    )
                },
            )
        } finally {
            source.close()
        }

        val analysis = ExcelAnalyzer.analyze(workbook)
        val questions = ExcelAnalyzer.parse(workbook, analysis.mappings)

        assertFalse(analysis.requiresManualMapping)
        assertEquals(438, questions.size)
        assertEquals(312, questions.count { it.type == QuestionType.SINGLE_CHOICE })
        assertEquals(125, questions.count { it.type == QuestionType.TRUE_FALSE })
        assertEquals(1, questions.count { it.type == QuestionType.SHORT_ANSWER })
    }

    @Test
    fun allTypesInOneSheetRequiresManualMappingButCanBeParsed() {
        val workbook = WorkbookData(
            "mixed.xlsx",
            listOf(
                SheetData(
                    "全部题目",
                    listOf(
                        listOf("题型", "题目", "A", "B", "答案"),
                        listOf("多选题", "请选择", "甲", "乙", "AB"),
                    ),
                ),
            ),
        )
        val analysis = ExcelAnalyzer.analyze(workbook)
        assertTrue(analysis.requiresManualMapping)
        assertTrue(ColumnField.TYPE in analysis.mappings.single().columns)

        val questions = ExcelAnalyzer.parse(workbook, analysis.mappings)
        assertEquals(1, questions.size)
        assertEquals(QuestionType.MULTIPLE_CHOICE, questions.single().type)
        assertEquals(setOf("A", "B"), questions.single().options.filter { it.isCorrect }.map { it.label }.toSet())
    }

    @Test
    fun exportedQuestionBankCanBeImportedAgain() {
        val original = listOf(
            QuizQuestion(
                id = 1,
                position = 0,
                type = QuestionType.SINGLE_CHOICE,
                prompt = "单选示例",
                options = listOf(
                    QuestionOption("A", "正确", true),
                    QuestionOption("B", "错误", false),
                ),
                answer = "A",
                explanation = "单选解析",
                sheetName = "单选题",
            ),
            QuizQuestion(
                id = 2,
                position = 1,
                type = QuestionType.MULTIPLE_CHOICE,
                prompt = "多选示例",
                options = listOf(
                    QuestionOption("A", "甲", true),
                    QuestionOption("B", "乙", false),
                    QuestionOption("C", "丙", true),
                ),
                answer = "AC",
                explanation = null,
                sheetName = "多选题",
            ),
            QuizQuestion(
                id = 3,
                position = 2,
                type = QuestionType.TRUE_FALSE,
                prompt = "判断示例",
                options = listOf(
                    QuestionOption("A", "对", false),
                    QuestionOption("B", "错", true),
                ),
                answer = "错",
                explanation = "判断解析",
                sheetName = "判断题",
            ),
        )
        val bytes = ByteArrayOutputStream().also { QuestionBankExporter.writeWorkbook(it, original) }.toByteArray()
        val source = Workbook.getWorkbook(ByteArrayInputStream(bytes))
        val workbook = try {
            WorkbookData(
                "round-trip.xls",
                source.sheets.map { sheet ->
                    SheetData(
                        sheet.name,
                        (0 until sheet.rows).map { row ->
                            (0 until sheet.columns).map { column -> sheet.getCell(column, row).contents.trim() }
                        },
                    )
                },
            )
        } finally {
            source.close()
        }

        val analysis = ExcelAnalyzer.analyze(workbook)
        val parsed = ExcelAnalyzer.parse(workbook, analysis.mappings)

        assertFalse(analysis.requiresManualMapping)
        assertEquals(3, parsed.size)
        assertEquals(setOf("A"), parsed[0].options.filter { it.isCorrect }.map { it.label }.toSet())
        assertEquals(setOf("A", "C"), parsed[1].options.filter { it.isCorrect }.map { it.label }.toSet())
        assertEquals("B", parsed[2].options.single { it.isCorrect }.label)
    }
}
