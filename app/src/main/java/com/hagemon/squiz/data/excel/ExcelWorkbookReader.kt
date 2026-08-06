package com.hagemon.squiz.data.excel

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import com.hagemon.squiz.model.SheetData
import com.hagemon.squiz.model.WorkbookData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import jxl.Workbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

object ExcelWorkbookReader {
    suspend fun read(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String,
    ): WorkbookData = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取这个文件")
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "xls" -> readXls(bytes, fileName)
            "xlsx" -> readXlsx(bytes, fileName)
            "csv" -> readCsv(bytes, fileName)
            else -> runCatching { readXlsx(bytes, fileName) }
                .recoverCatching { readXls(bytes, fileName) }
                .getOrElse { error("暂不支持这个文件格式") }
        }
    }

    private fun readXls(bytes: ByteArray, fileName: String): WorkbookData {
        val workbook = Workbook.getWorkbook(ByteArrayInputStream(bytes))
        return try {
            WorkbookData(
                fileName = fileName,
                sheets = workbook.sheets.map { sheet ->
                    SheetData(
                        name = sheet.name,
                        rows = (0 until sheet.rows).map { row ->
                            (0 until sheet.columns).map { column ->
                                sheet.getCell(column, row).contents.trim()
                            }.trimTrailingEmptyCells()
                        },
                    )
                },
            )
        } finally {
            workbook.close()
        }
    }

    private fun readXlsx(bytes: ByteArray, fileName: String): WorkbookData {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    entries[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val workbookXml = entries["xl/workbook.xml"] ?: error("不是有效的 Excel 工作簿")
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val relationships = entries["xl/_rels/workbook.xml.rels"]?.let(::parseRelationships).orEmpty()
        val sheetRefs = parseSheetRefs(workbookXml)

        val sheets = sheetRefs.mapNotNull { (name, relationId) ->
            val target = relationships[relationId] ?: return@mapNotNull null
            val normalizedTarget = if (target.startsWith("/")) {
                target.removePrefix("/")
            } else {
                "xl/${target.removePrefix("../")}".replace("xl/xl/", "xl/")
            }
            val sheetXml = entries[normalizedTarget] ?: return@mapNotNull null
            SheetData(name, parseSheet(sheetXml, sharedStrings))
        }
        require(sheets.isNotEmpty()) { "工作簿里没有可读取的 Sheet" }
        return WorkbookData(fileName, sheets)
    }

    private fun readCsv(bytes: ByteArray, fileName: String): WorkbookData {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += cell.toString().trim()
                    cell.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString().trim()
                    rows += row.trimTrailingEmptyCells()
                    row = mutableListOf()
                    cell.clear()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString().trim()
            rows += row.trimTrailingEmptyCells()
        }
        return WorkbookData(fileName, listOf(SheetData("题目", rows)))
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val parser = parser(xml)
        val values = mutableListOf<String>()
        var insideItem = false
        var current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { insideItem = true; current = StringBuilder() }
                    "t" -> if (insideItem) current.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    values += current.toString()
                    insideItem = false
                }
            }
            parser.next()
        }
        return values
    }

    private fun parseRelationships(xml: ByteArray): Map<String, String> {
        val parser = parser(xml)
        val values = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.attribute("Id")
                val target = parser.attribute("Target")
                if (id != null && target != null) values[id] = target
            }
            parser.next()
        }
        return values
    }

    private fun parseSheetRefs(xml: ByteArray): List<Pair<String, String>> {
        val parser = parser(xml)
        val values = mutableListOf<Pair<String, String>>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.attribute("name")
                val relationId = parser.attribute("id")
                if (name != null && relationId != null) values += name to relationId
            }
            parser.next()
        }
        return values
    }

    private fun parseSheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = parser(xml)
        val rows = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var cellColumn = 0
        var cellType: String? = null
        var cellValue = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        cellColumn = parser.attribute("r")?.takeWhile(Char::isLetter)?.toColumnIndex() ?: row.size
                        cellType = parser.attribute("t")
                        cellValue = ""
                    }
                    "v", "t" -> cellValue += parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val value = if (cellType == "s") {
                            cellValue.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
                        } else {
                            cellValue
                        }
                        row[cellColumn] = value.trim()
                    }
                    "row" -> {
                        val max = row.keys.maxOrNull() ?: -1
                        rows += (0..max).map { row[it].orEmpty() }.trimTrailingEmptyCells()
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun parser(xml: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setInput(ByteArrayInputStream(xml), "UTF-8")
    }

    private fun XmlPullParser.attribute(name: String): String? =
        (0 until attributeCount).firstNotNullOfOrNull { index ->
            getAttributeValue(index).takeIf { getAttributeName(index) == name }
        }

    private fun String.toColumnIndex(): Int = fold(0) { value, char ->
        value * 26 + (char.uppercaseChar() - 'A' + 1)
    } - 1

    private fun List<String>.trimTrailingEmptyCells(): List<String> =
        dropLastWhile(String::isBlank)
}
