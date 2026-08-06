package com.hagemon.squiz.data.excel

import android.content.ContentResolver
import android.net.Uri
import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.QuizQuestion
import java.io.File
import java.io.OutputStream
import jxl.Workbook
import jxl.format.Alignment
import jxl.format.Colour
import jxl.format.VerticalAlignment
import jxl.write.Label
import jxl.write.Number as NumberCell
import jxl.write.WritableCellFormat
import jxl.write.WritableFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QuestionBankExporter {
    suspend fun export(
        contentResolver: ContentResolver,
        uri: Uri,
        questions: List<QuizQuestion>,
    ) = withContext(Dispatchers.IO) {
        contentResolver.openOutputStream(uri)?.use { writeWorkbook(it, questions) }
            ?: error("无法创建题库文件")
    }

    suspend fun export(file: File, questions: List<QuizQuestion>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.outputStream().use { writeWorkbook(it, questions) }
    }

    internal fun writeWorkbook(output: OutputStream, questions: List<QuizQuestion>) {
        require(questions.isNotEmpty()) { "题库中暂无可导出的题目" }
        val workbook = Workbook.createWorkbook(output)
        try {
            val headerFormat = WritableCellFormat(WritableFont(WritableFont.ARIAL, 11, WritableFont.BOLD)).apply {
                setBackground(Colour.LAVENDER)
                alignment = Alignment.CENTRE
                verticalAlignment = VerticalAlignment.CENTRE
            }
            val bodyFormat = WritableCellFormat().apply {
                wrap = true
                verticalAlignment = VerticalAlignment.CENTRE
            }
            val groups = exportGroups(questions)
            groups.forEachIndexed { sheetIndex, group ->
                val sheet = workbook.createSheet(group.name, sheetIndex)
                group.headers.forEachIndexed { column, header ->
                    sheet.addCell(Label(column, 0, header, headerFormat))
                    sheet.setColumnView(
                        column,
                        when (header) {
                            "题目", "解析" -> 38
                            "序号" -> 8
                            "答案", "题型" -> 16
                            else -> 14
                        },
                    )
                }
                group.questions.forEachIndexed { rowIndex, question ->
                    val row = group.row(question, rowIndex + 1)
                    row.forEachIndexed { column, value ->
                        if (column == 0) sheet.addCell(NumberCell(column, rowIndex + 1, (rowIndex + 1).toDouble(), bodyFormat))
                        else sheet.addCell(Label(column, rowIndex + 1, value, bodyFormat))
                    }
                    sheet.setRowView(rowIndex + 1, 620)
                }
                sheet.setRowView(0, 520)
            }
            workbook.write()
        } finally {
            workbook.close()
        }
    }
}

private data class ExportGroup(
    val name: String,
    val headers: List<String>,
    val questions: List<QuizQuestion>,
    val row: (QuizQuestion, Int) -> List<String>,
)

private fun exportGroups(questions: List<QuizQuestion>): List<ExportGroup> {
    val choiceHeaders = listOf("序号", "题目", "A", "B", "C", "D", "E", "F", "G", "答案", "解析")
    val choiceRow: (QuizQuestion, Int) -> List<String> = { question, index ->
        val optionByLabel = question.options.associateBy { it.label.uppercase() }
        listOf(index.toString(), question.prompt) +
            ('A'..'G').map { optionByLabel[it.toString()]?.text.orEmpty() } +
            listOf(question.exportAnswer, question.explanation.orEmpty())
    }
    val result = mutableListOf<ExportGroup>()
    listOf(
        QuestionType.SINGLE_CHOICE to "单选题",
        QuestionType.MULTIPLE_CHOICE to "多选题",
    ).forEach { (type, name) ->
        questions.filter { it.type == type }.takeIf(List<QuizQuestion>::isNotEmpty)?.let {
            result += ExportGroup(name, choiceHeaders, it, choiceRow)
        }
    }
    questions.filter { it.type == QuestionType.TRUE_FALSE }.takeIf(List<QuizQuestion>::isNotEmpty)?.let { judgement ->
        result += ExportGroup(
            name = "判断题",
            headers = listOf("序号", "题目", "答案", "解析"),
            questions = judgement,
            row = { question, index ->
                val correct = question.options.firstOrNull { it.isCorrect }?.text ?: question.answer
                listOf(index.toString(), question.prompt, correct, question.explanation.orEmpty())
            },
        )
    }
    questions.filter { it.type !in supportedExportTypes }.takeIf(List<QuizQuestion>::isNotEmpty)?.let { others ->
        result += ExportGroup(
            name = "其他题目",
            headers = listOf("序号", "题型", "题目", "A", "B", "C", "D", "E", "F", "G", "答案", "解析"),
            questions = others,
            row = { question, index ->
                val optionByLabel = question.options.associateBy { it.label.uppercase() }
                listOf(index.toString(), question.type.displayName, question.prompt) +
                    ('A'..'G').map { optionByLabel[it.toString()]?.text.orEmpty() } +
                    listOf(question.exportAnswer, question.explanation.orEmpty())
            },
        )
    }
    return result
}

private val supportedExportTypes = setOf(
    QuestionType.SINGLE_CHOICE,
    QuestionType.MULTIPLE_CHOICE,
    QuestionType.TRUE_FALSE,
)

private val QuizQuestion.exportAnswer: String
    get() = options.filter { it.isCorrect }.joinToString("") { it.label }.ifBlank { answer }
