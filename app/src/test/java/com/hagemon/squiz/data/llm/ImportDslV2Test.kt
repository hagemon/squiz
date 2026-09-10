package com.hagemon.squiz.data.llm

import com.hagemon.squiz.model.QuestionType
import com.hagemon.squiz.model.SheetData
import com.hagemon.squiz.model.WorkbookData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDslV2Test {
    @Test
    fun mixedQuestionTypesUseOrderedRulesAndValueTransforms() {
        val sheet = SheetData(
            "混合题",
            listOf(
                listOf("类型编码", "问题", "第一项", "第二项", "正确项", "说明"),
                listOf("SC", "2 + 2 = ?", "4", "5", "第一项", "数学常识"),
                listOf("TF", "地球是球形", "", "", "YES", ""),
            ),
        )
        val script = ImportDslV2Parser.parse(
            """
            {
              "version": 2,
              "sheet": {
                "sheetName": "混合题",
                "headerRowIndex": 0,
                "rowRules": [
                  {
                    "name": "单选编码",
                    "when": [{"column": 0, "operator": "IN", "values": ["SC"]}],
                    "type": "SINGLE_CHOICE",
                    "fields": {
                      "content": {"columns": [1], "operations": [{"op": "TRIM"}]},
                      "answer": {"columns": [4], "operations": [{"op": "VALUE_MAP", "values": {"第一项": "A", "第二项": "B"}}]},
                      "explanation": {"columns": [5]},
                      "options": {"mode": "COLUMNS", "items": [
                        {"label": "A", "value": {"columns": [2]}},
                        {"label": "B", "value": {"columns": [3]}}
                      ]}
                    }
                  },
                  {
                    "name": "判断编码",
                    "when": [{"column": 0, "operator": "EQUALS", "values": ["TF"]}],
                    "type": "TRUE_FALSE",
                    "fields": {
                      "content": {"columns": [1]},
                      "answer": {"columns": [4], "operations": [{"op": "VALUE_MAP", "values": {"YES": "正确", "NO": "错误"}}]}
                    }
                  }
                ]
              }
            }
            """.trimIndent(),
            sheet,
        )

        val result = ImportDslV2Executor.execute(WorkbookData("mixed.xlsx", listOf(sheet)), ImportScriptV2(sheets = listOf(script)))

        assertEquals(2, result.convertedRows)
        assertEquals(0, result.invalidRows)
        assertEquals(listOf(QuestionType.SINGLE_CHOICE, QuestionType.TRUE_FALSE), result.questions.map { it.type })
        assertEquals("A", result.questions[0].answer)
        assertEquals("A", result.questions[1].answer)
        assertEquals("数学常识", result.questions[0].explanation)
    }

    @Test
    fun multipleSheetsCanUseDifferentSchemasAndSplitOptionsFromOneCell() {
        val general = SheetData(
            "通用题",
            listOf(
                listOf("题型", "题干", "选项集合", "答案"),
                listOf("多选", "选择偶数", "A. 1|B. 2|C. 3|D. 4", "BD"),
            ),
        )
        val professional = SheetData(
            "专业题",
            listOf(
                listOf("分类", "前半句", "后半句", "参考答案"),
                listOf("简答", "说明 TCP", "三次握手", "建立可靠连接"),
            ),
        )
        val generalScript = ImportDslV2Parser.parse(
            """
            {"version":2,"sheet":{"sheetName":"通用题","headerRowIndex":0,"rowRules":[{
              "name":"多选","when":[{"column":0,"operator":"CONTAINS","values":["多选"]}],"type":"MULTIPLE_CHOICE",
              "fields":{"content":{"columns":[1]},"answer":{"columns":[3]},"options":{"mode":"SPLIT_CELL","source":{"columns":[2]},"splitStyle":"PIPE"}}
            }]}}
            """.trimIndent(),
            general,
        )
        val professionalScript = ImportDslV2Parser.parse(
            """
            {"version":2,"sheet":{"sheetName":"专业题","headerRowIndex":0,"rowRules":[{
              "name":"简答","when":[{"column":0,"operator":"EQUALS","values":["简答"]}],"type":"SHORT_ANSWER",
              "fields":{"content":{"columns":[1,2],"mode":"JOIN","separator":"："},"answer":{"columns":[3]}}
            }]}}
            """.trimIndent(),
            professional,
        )

        val result = ImportDslV2Executor.execute(
            WorkbookData("categories.xlsx", listOf(general, professional)),
            ImportScriptV2(sheets = listOf(generalScript, professionalScript)),
        )

        assertEquals(2, result.convertedRows)
        assertEquals(setOf("B", "D"), result.questions[0].options.filter { it.isCorrect }.map { it.label }.toSet())
        assertEquals("说明 TCP：三次握手", result.questions[1].prompt)
        assertEquals(listOf("通用题", "专业题"), result.questions.map { it.sheetName })
    }

    @Test
    fun profilerScansWholeSheetButReturnsBoundedRepresentativeContext() {
        val rows = mutableListOf(listOf("类型", "题目", "答案"))
        repeat(18) { rows += listOf("单选", "问题 $it", "A") }
        rows += listOf("判断题特殊编码", "最后才出现的类型", "正确")
        val profile = WorkbookProfiler.profile(SheetData("后置题型", rows))

        assertEquals(20, profile.getInt("rowCount"))
        assertTrue(profile.toString().contains("判断题特殊编码"))
        assertTrue(profile.getJSONArray("representativeRows").length() <= 24)
        assertTrue(profile.getJSONArray("candidateHeaderRows").length() <= 12)
    }

    @Test
    fun profilerFindsFrequentCustomDelimiterWithoutSendingWholeColumn() {
        val sheet = SheetData(
            "单列选项",
            listOf(
                listOf("题目", "选项集", "答案"),
                listOf("第一题", "A. 甲@#B. 乙@#C. 丙", "B"),
                listOf("第二题", "A. 是@#B. 否", "A"),
                listOf("第三题", "A. 上@#B. 下@#C. 左@#D. 右", "D"),
            ),
        )

        val profile = WorkbookProfiler.profile(sheet)
        val optionColumn = profile.getJSONArray("columnProfiles").getJSONObject(1)
        val candidates = optionColumn.getJSONArray("delimiterCandidates")

        assertTrue((0 until candidates.length()).any { candidates.getJSONObject(it).getString("delimiter") == "@#" })
        assertEquals(3, optionColumn.getInt("nonBlankCount"))
    }

    @Test
    fun literalDelimiterSplitsVariableOptionCounts() {
        val sheet = SheetData(
            "单列选项",
            listOf(
                listOf("题目", "选项集", "答案"),
                listOf("三选一", "A. 甲@#B. 乙@#C. 丙", "B"),
                listOf("四选一", "A. 东@#B. 西@#C. 南@#D. 北", "D"),
            ),
        )
        val script = ImportDslV2Parser.parse(
            """
            {"version":2,"sheet":{"sheetName":"单列选项","headerRowIndex":0,"rowRules":[{
              "name":"单选","when":[],"type":"SINGLE_CHOICE","fields":{
                "content":{"columns":[0]},"answer":{"columns":[2]},
                "options":{"mode":"SPLIT_CELL","source":{"columns":[1]},"delimiter":"@#"}
              }
            }]}}
            """.trimIndent(),
            sheet,
        )

        val result = ImportDslV2Executor.execute(
            WorkbookData("single-column.xlsx", listOf(sheet)),
            ImportScriptV2(sheets = listOf(script)),
        )

        assertEquals(listOf(3, 4), result.questions.map { it.options.size })
        assertEquals("B", result.questions[0].answer)
        assertEquals("D", result.questions[1].answer)
    }

    @Test
    fun executorReportsUnmatchedAndInvalidRowsWithoutAbortingOtherRows() {
        val sheet = SheetData(
            "诊断",
            listOf(
                listOf("类型", "题目", "答案"),
                listOf("简答", "有效题目", "有效答案"),
                listOf("未知", "无法匹配", "A"),
                listOf("简答", "", "缺少题目"),
            ),
        )
        val rule = RowRuleV2(
            name = "简答",
            predicates = listOf(RowPredicateV2(0, PredicateOperatorV2.EQUALS, listOf("简答"))),
            type = QuestionType.SHORT_ANSWER,
            content = ValueSpecV2(columns = listOf(1)),
            answer = ValueSpecV2(columns = listOf(2)),
            explanation = null,
            options = null,
        )

        val result = ImportDslV2Executor.execute(
            WorkbookData("diagnostic.xlsx", listOf(sheet)),
            ImportScriptV2(sheets = listOf(SheetScriptV2("诊断", 0, listOf(rule)))),
        )

        assertEquals(1, result.convertedRows)
        assertEquals(1, result.unmatchedRows)
        assertEquals(1, result.invalidRows)
        assertEquals(2, result.diagnostics.size)
    }

    @Test
    fun executorSortsByQuestionTypeThenOriginalSheetAndRowOrder() {
        val firstSheet = SheetData(
            "第一页",
            listOf(
                listOf("类型", "题目", "答案", "A", "B"),
                listOf("多选", "第一页多选", "AB", "甲", "乙"),
                listOf("判断", "第一页判断", "正确", "", ""),
                listOf("单选", "第一页单选", "A", "甲", "乙"),
            ),
        )
        val secondSheet = SheetData(
            "第二页",
            listOf(
                listOf("类型", "题目", "答案", "A", "B"),
                listOf("判断", "第二页判断", "错误", "", ""),
                listOf("单选", "第二页单选", "B", "甲", "乙"),
                listOf("多选", "第二页多选", "AB", "甲", "乙"),
            ),
        )

        fun rule(label: String, type: QuestionType) = RowRuleV2(
            name = label,
            predicates = listOf(RowPredicateV2(0, PredicateOperatorV2.EQUALS, listOf(label))),
            type = type,
            content = ValueSpecV2(columns = listOf(1)),
            answer = ValueSpecV2(columns = listOf(2)),
            explanation = null,
            options = if (type == QuestionType.TRUE_FALSE) null else OptionsSpecV2(
                mode = OptionsModeV2.COLUMNS,
                items = listOf(
                    OptionItemV2("A", ValueSpecV2(columns = listOf(3))),
                    OptionItemV2("B", ValueSpecV2(columns = listOf(4))),
                ),
            ),
        )
        val rules = listOf(
            rule("单选", QuestionType.SINGLE_CHOICE),
            rule("多选", QuestionType.MULTIPLE_CHOICE),
            rule("判断", QuestionType.TRUE_FALSE),
        )

        val result = ImportDslV2Executor.execute(
            WorkbookData("ordered.xlsx", listOf(firstSheet, secondSheet)),
            ImportScriptV2(
                sheets = listOf(
                    SheetScriptV2(firstSheet.name, 0, rules),
                    SheetScriptV2(secondSheet.name, 0, rules),
                ),
            ),
        )

        assertEquals(
            listOf(
                "第一页单选",
                "第二页单选",
                "第一页多选",
                "第二页多选",
                "第一页判断",
                "第二页判断",
            ),
            result.questions.map { it.prompt },
        )
    }
}
