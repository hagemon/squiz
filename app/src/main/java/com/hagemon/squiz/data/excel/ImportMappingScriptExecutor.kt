package com.hagemon.squiz.data.excel

import com.hagemon.squiz.model.ImportMappingScript
import com.hagemon.squiz.model.ParsedQuestion
import com.hagemon.squiz.model.WorkbookData

object ImportMappingScriptExecutor {
    fun execute(workbook: WorkbookData, script: ImportMappingScript): List<ParsedQuestion> {
        require(script.version == 1) { "不支持的转换脚本版本" }
        require(script.sheets.map { it.sheetName }.toSet() == workbook.sheets.map { it.name }.toSet()) {
            "转换脚本与当前工作簿不匹配"
        }
        return ExcelAnalyzer.parse(workbook, script.sheets)
    }
}
