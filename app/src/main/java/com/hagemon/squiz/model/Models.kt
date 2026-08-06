package com.hagemon.squiz.model

import java.util.UUID

enum class LibraryItemType {
    FOLDER,
    QUESTION_BANK,
}

data class LibraryItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: LibraryItemType,
    val parentId: String? = null,
    val uri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val importedAt: Long? = null,
    val lastOpenedAt: Long? = null,
    val questionCount: Int = 0,
)

val LibraryItem.activityAt: Long
    get() = maxOf(importedAt ?: 0L, lastOpenedAt ?: 0L, createdAt)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class ColorTheme {
    LAVENDER,
    ROSE,
    OCEAN,
    SAGE,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorTheme: ColorTheme = ColorTheme.LAVENDER,
    val textScale: Float = 1f,
    val singleChoiceAutoNext: Boolean = false,
)

enum class QuestionType(val displayName: String) {
    SINGLE_CHOICE("单选题"),
    MULTIPLE_CHOICE("多选题"),
    TRUE_FALSE("判断题"),
    CALCULATION("计算题"),
    SHORT_ANSWER("简答题"),
    UNKNOWN("未指定"),
}

data class QuestionOption(
    val label: String,
    val text: String,
    val isCorrect: Boolean = false,
)

data class ParsedQuestion(
    val type: QuestionType,
    val prompt: String,
    val options: List<QuestionOption>,
    val answer: String,
    val explanation: String? = null,
    val sheetName: String,
    val sourceRow: Int,
)

data class QuizQuestion(
    val id: Long,
    val position: Int,
    val type: QuestionType,
    val prompt: String,
    val options: List<QuestionOption>,
    val answer: String,
    val explanation: String? = null,
    val sheetName: String,
)

enum class PracticeMode {
    NORMAL,
    WRONG,
    GUESSED,
}

data class PracticeSession(
    val id: String,
    val bankId: String,
    val mode: PracticeMode,
    val startedAt: Long,
    val completedAt: Long?,
    val currentPosition: Int,
    val totalQuestions: Int,
    val answeredCount: Int = 0,
    val correctCount: Int = 0,
) {
    val isCompleted: Boolean get() = completedAt != null
}

data class PracticeAnswerSnapshot(
    val questionId: Long,
    val position: Int,
    val selectedLabels: Set<String>,
    val isCorrect: Boolean,
    val answeredAt: Long,
)

data class WorkbookData(
    val fileName: String,
    val sheets: List<SheetData>,
)

data class SheetData(
    val name: String,
    val rows: List<List<String>>,
)

enum class ColumnField(val displayName: String) {
    NONE("不导入"),
    TYPE("题型"),
    CONTENT("题目内容"),
    OPTION_A("选项 A"),
    OPTION_B("选项 B"),
    OPTION_C("选项 C"),
    OPTION_D("选项 D"),
    OPTION_E("选项 E"),
    OPTION_F("选项 F"),
    OPTION_G("选项 G"),
    ANSWER("答案"),
    EXPLANATION("解析"),
}

val ColumnField.optionLabel: String?
    get() = when (this) {
        ColumnField.OPTION_A -> "A"
        ColumnField.OPTION_B -> "B"
        ColumnField.OPTION_C -> "C"
        ColumnField.OPTION_D -> "D"
        ColumnField.OPTION_E -> "E"
        ColumnField.OPTION_F -> "F"
        ColumnField.OPTION_G -> "G"
        else -> null
    }

data class SheetMapping(
    val sheetName: String,
    val headerRowIndex: Int,
    val fixedType: QuestionType,
    val columns: List<ColumnField>,
)

data class ImportAnalysis(
    val workbook: WorkbookData,
    val mappings: List<SheetMapping>,
    val requiresManualMapping: Boolean,
)
