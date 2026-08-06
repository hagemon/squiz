package com.hagemon.squiz.data.excel

import android.content.ContentResolver
import android.net.Uri
import java.io.OutputStream
import jxl.Workbook
import jxl.format.Alignment
import jxl.format.Colour
import jxl.format.PageOrientation
import jxl.format.VerticalAlignment
import jxl.write.Label
import jxl.write.Number as NumberCell
import jxl.write.WritableCellFormat
import jxl.write.WritableFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StandardTemplateExporter {
    suspend fun export(contentResolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        contentResolver.openOutputStream(uri)?.use(::writeWorkbook)
            ?: error("无法创建模板文件")
    }

    internal fun writeWorkbook(output: OutputStream) {
        val workbook = Workbook.createWorkbook(output)
        try {
            val headerFont = WritableFont(WritableFont.ARIAL, 11, WritableFont.BOLD)
            val headerFormat = WritableCellFormat(headerFont).apply {
                setBackground(Colour.LAVENDER)
                alignment = Alignment.CENTRE
                verticalAlignment = VerticalAlignment.CENTRE
            }
            val exampleFormat = WritableCellFormat().apply {
                wrap = true
                verticalAlignment = VerticalAlignment.CENTRE
            }
            val sheets = listOf(
                TemplateSheet(
                    name = "单选题",
                    headers = listOf("序号", "题目", "A", "B", "C", "D", "E", "F", "G", "答案", "解析"),
                    example = listOf(
                        "1", "某学习小组准备使用 squiz 整理跨学科期末复习资料，希望应用能根据系统主题自动协调界面颜色，同时保持清晰、快速且适合长时间阅读。下列哪一项最符合 Material You 的设计特点？", "动态配色与个性化界面", "只能使用黑白配色",
                        "完全取消动画", "只支持桌面系统", "", "", "", "A", "Material You 会根据主题提供协调且个性化的界面配色。",
                    ),
                ),
                TemplateSheet(
                    name = "多选题",
                    headers = listOf("序号", "题目", "A", "B", "C", "D", "E", "F", "G", "答案", "解析"),
                    example = listOf(
                        "1", "老师需要把包含数百道题目的资料整理成可导入的电子表格，同时希望文件能够被常见办公软件读取。以下哪些扩展名属于本应用支持或常见的电子表格数据格式？", ".xls", ".xlsx", ".mp3", ".csv",
                        "", "", "", "ABD", "多选题答案可填写多个选项字母，例如 ABD。",
                    ),
                ),
                TemplateSheet(
                    name = "判断题",
                    headers = listOf("序号", "题目", "答案", "解析"),
                    example = listOf(
                        "1", "当“答对自动翻页”设置处于关闭状态时，用户完成单选题、多选题或判断题的作答后，仍然应该能够通过页面下方始终可见的“下一题”按钮继续答题。",
                        "对", "判断题答案可填写“对”或“错”。",
                    ),
                ),
            )
            sheets.forEachIndexed { sheetIndex, template ->
                val sheet = workbook.createSheet(template.name, sheetIndex)
                template.headers.forEachIndexed { column, title ->
                    sheet.addCell(Label(column, 0, title, headerFormat))
                    sheet.setColumnView(
                        column,
                        when (title) {
                            "题目", "解析" -> 36
                            "答案" -> 18
                            "序号" -> 8
                            else -> 14
                        },
                    )
                }
                template.example.forEachIndexed { column, value ->
                    if (column == 0) {
                        sheet.addCell(NumberCell(column, 1, value.toDouble(), exampleFormat))
                    } else {
                        sheet.addCell(Label(column, 1, value, exampleFormat))
                    }
                }
                sheet.setRowView(0, 520)
                sheet.setRowView(1, 720)
                sheet.settings.apply {
                    orientation = PageOrientation.LANDSCAPE
                    fitToPages = true
                    fitWidth = 1
                    fitHeight = 1
                    isHorizontalCentre = true
                }
            }
            workbook.write()
        } finally {
            workbook.close()
        }
    }
}

private data class TemplateSheet(
    val name: String,
    val headers: List<String>,
    val example: List<String>,
)
