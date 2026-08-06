package com.hagemon.squiz.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId")],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "question_banks",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId")],
)
data class QuestionBankEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val sourceUri: String?,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val questionCount: Int,
)

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = QuestionBankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bankId")],
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: String,
    val position: Int,
    val type: String,
    val prompt: String,
    val answer: String,
    val explanation: String?,
    val sheetName: String,
    val sourceRow: Int,
)

@Entity(
    tableName = "options",
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("questionId")],
)
data class OptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: Long,
    val label: String,
    val text: String,
    val position: Int,
    val isCorrect: Boolean,
)

@Entity(
    tableName = "practice_sessions",
    foreignKeys = [
        ForeignKey(
            entity = QuestionBankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bankId"), Index(value = ["bankId", "completedAt"])],
)
data class PracticeSessionEntity(
    @PrimaryKey val id: String,
    val bankId: String,
    val mode: String,
    val startedAt: Long,
    val completedAt: Long?,
    val currentPosition: Int,
    val totalQuestions: Int,
)

@Entity(
    tableName = "session_questions",
    primaryKeys = ["sessionId", "questionId"],
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("questionId"), Index(value = ["sessionId", "orderIndex"], unique = true)],
)
data class SessionQuestionEntity(
    val sessionId: String,
    val questionId: Long,
    val orderIndex: Int,
)

@Entity(
    tableName = "practice_answers",
    primaryKeys = ["sessionId", "questionId"],
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("questionId")],
)
data class PracticeAnswerEntity(
    val sessionId: String,
    val questionId: Long,
    val selectedLabels: String,
    val isCorrect: Boolean,
    val answeredAt: Long,
)

@Entity(
    tableName = "guessed_questions",
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class GuessedQuestionEntity(
    @PrimaryKey val questionId: Long,
    val sessionId: String,
    val markedAt: Long,
)

data class QuestionWithOptions(
    @Embedded val question: QuestionEntity,
    @Relation(parentColumn = "id", entityColumn = "questionId")
    val options: List<OptionEntity>,
)

data class SessionQuestionWithOptions(
    @Embedded val question: QuestionEntity,
    val practicePosition: Int,
    @Relation(parentColumn = "id", entityColumn = "questionId")
    val options: List<OptionEntity>,
)

data class PracticeSessionSummaryRow(
    @Embedded val session: PracticeSessionEntity,
    val answeredCount: Int,
    val correctCount: Int,
)

data class PracticeAnswerRow(
    val questionId: Long,
    val position: Int,
    val selectedLabels: String,
    val isCorrect: Boolean,
    val answeredAt: Long,
)

@Dao
interface SquizDao {
    @Query("SELECT * FROM folders ORDER BY createdAt DESC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM question_banks ORDER BY importedAt DESC")
    fun observeBanks(): Flow<List<QuestionBankEntity>>

    @Insert
    suspend fun insertFolder(folder: FolderEntity)

    @Insert
    suspend fun insertBank(bank: QuestionBankEntity)

    @Insert
    suspend fun insertQuestion(question: QuestionEntity): Long

    @Insert
    suspend fun insertOptions(options: List<OptionEntity>)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: String, name: String)

    @Query("UPDATE question_banks SET name = :name WHERE id = :id")
    suspend fun renameBank(id: String, name: String)

    @Query("UPDATE folders SET parentId = :parentId WHERE id = :id")
    suspend fun moveFolder(id: String, parentId: String?)

    @Query("UPDATE question_banks SET parentId = :parentId WHERE id = :id")
    suspend fun moveBank(id: String, parentId: String?)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolder(id: String): FolderEntity?

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("DELETE FROM question_banks WHERE id = :id")
    suspend fun deleteBank(id: String)

    @Query("UPDATE question_banks SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun touchBank(id: String, openedAt: Long)

    @Transaction
    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY position ASC LIMIT :limit OFFSET :offset")
    suspend fun getQuestionsPage(bankId: String, offset: Int, limit: Int): List<QuestionWithOptions>

    @Transaction
    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY position ASC")
    suspend fun getAllQuestions(bankId: String): List<QuestionWithOptions>

    @Query("SELECT id FROM questions WHERE bankId = :bankId ORDER BY position ASC")
    suspend fun getQuestionIds(bankId: String): List<Long>

    @Query("SELECT id FROM questions WHERE bankId = :bankId AND type IN (:types) ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomQuestionIds(bankId: String, types: List<String>, limit: Int): List<Long>

    @Query(
        """
        SELECT a.questionId FROM practice_answers a
        INNER JOIN questions q ON q.id = a.questionId
        WHERE q.bankId = :bankId AND a.isCorrect = 0
        GROUP BY a.questionId
        HAVING COUNT(*) >= :minimumWrongCount
        ORDER BY COUNT(*) DESC, MIN(q.position) ASC
        """,
    )
    suspend fun getWrongQuestionIds(bankId: String, minimumWrongCount: Int): List<Long>

    @Insert
    suspend fun insertPracticeSession(session: PracticeSessionEntity)

    @Insert
    suspend fun insertSessionQuestions(questions: List<SessionQuestionEntity>)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertPracticeAnswer(answer: PracticeAnswerEntity)

    @Query("UPDATE practice_sessions SET currentPosition = :position WHERE id = :sessionId AND completedAt IS NULL")
    suspend fun updatePracticePosition(sessionId: String, position: Int)

    @Query("UPDATE practice_sessions SET completedAt = :completedAt, currentPosition = totalQuestions - 1 WHERE id = :sessionId")
    suspend fun completePracticeSession(sessionId: String, completedAt: Long)

    @Query(
        """
        UPDATE practice_sessions
        SET completedAt = :completedAt, currentPosition = totalQuestions - 1
        WHERE id = :sessionId
          AND completedAt IS NULL
          AND (SELECT COUNT(*) FROM practice_answers WHERE sessionId = :sessionId) >= totalQuestions
        """,
    )
    suspend fun completePracticeIfAllAnswered(sessionId: String, completedAt: Long)

    @Query(
        """
        UPDATE practice_sessions
        SET completedAt = COALESCE(
                (SELECT MAX(answeredAt) FROM practice_answers WHERE sessionId = practice_sessions.id),
                startedAt
            ),
            currentPosition = totalQuestions - 1
        WHERE bankId = :bankId
          AND completedAt IS NULL
          AND (SELECT COUNT(*) FROM practice_answers WHERE sessionId = practice_sessions.id) >= totalQuestions
        """,
    )
    suspend fun reconcileCompletedSessions(bankId: String)

    @Query("DELETE FROM practice_sessions WHERE id IN (:sessionIds)")
    suspend fun deletePracticeSessions(sessionIds: List<String>)

    @Query(
        """
        SELECT s.*,
               COUNT(a.questionId) AS answeredCount,
               COALESCE(SUM(CASE WHEN a.isCorrect = 1 THEN 1 ELSE 0 END), 0) AS correctCount
        FROM practice_sessions s
        LEFT JOIN practice_answers a ON a.sessionId = s.id
        WHERE s.bankId = :bankId
        GROUP BY s.id
        ORDER BY s.startedAt DESC
        """,
    )
    fun observePracticeSessions(bankId: String): Flow<List<PracticeSessionSummaryRow>>

    @Query(
        """
        SELECT s.*,
               COUNT(a.questionId) AS answeredCount,
               COALESCE(SUM(CASE WHEN a.isCorrect = 1 THEN 1 ELSE 0 END), 0) AS correctCount
        FROM practice_sessions s
        LEFT JOIN practice_answers a ON a.sessionId = s.id
        WHERE s.bankId = :bankId AND s.mode = 'NORMAL' AND s.completedAt IS NULL
        GROUP BY s.id
        ORDER BY s.startedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestUnfinishedPractice(bankId: String): PracticeSessionSummaryRow?

    @Query(
        """
        SELECT s.*,
               COUNT(a.questionId) AS answeredCount,
               COALESCE(SUM(CASE WHEN a.isCorrect = 1 THEN 1 ELSE 0 END), 0) AS correctCount
        FROM practice_sessions s
        LEFT JOIN practice_answers a ON a.sessionId = s.id
        WHERE s.id = :sessionId
        GROUP BY s.id
        """,
    )
    suspend fun getPracticeSession(sessionId: String): PracticeSessionSummaryRow?

    @Transaction
    @Query(
        """
        SELECT q.*, sq.orderIndex AS practicePosition
        FROM session_questions sq
        INNER JOIN questions q ON q.id = sq.questionId
        WHERE sq.sessionId = :sessionId
        ORDER BY sq.orderIndex ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getSessionQuestionsPage(sessionId: String, offset: Int, limit: Int): List<SessionQuestionWithOptions>

    @Transaction
    @Query("SELECT * FROM questions WHERE id IN (:questionIds)")
    suspend fun getQuestionsByIds(questionIds: List<Long>): List<QuestionWithOptions>

    @Query(
        """
        SELECT a.questionId, sq.orderIndex AS position, a.selectedLabels, a.isCorrect, a.answeredAt
        FROM practice_answers a
        INNER JOIN session_questions sq
            ON sq.sessionId = a.sessionId AND sq.questionId = a.questionId
        WHERE a.sessionId = :sessionId
        ORDER BY sq.orderIndex ASC
        """,
    )
    suspend fun getPracticeAnswers(sessionId: String): List<PracticeAnswerRow>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT a.questionId FROM practice_answers a
            INNER JOIN questions q ON q.id = a.questionId
            WHERE q.bankId = :bankId AND a.isCorrect = 0
            GROUP BY a.questionId
            HAVING COUNT(*) >= :minimumWrongCount
        )
        """,
    )
    suspend fun getWrongQuestionCount(bankId: String, minimumWrongCount: Int): Int

    @Query(
        """
        SELECT COALESCE(MAX(wrongCount), 0) FROM (
            SELECT COUNT(*) AS wrongCount FROM practice_answers a
            INNER JOIN questions q ON q.id = a.questionId
            WHERE q.bankId = :bankId AND a.isCorrect = 0
            GROUP BY a.questionId
        )
        """,
    )
    suspend fun getMaximumWrongCount(bankId: String): Int

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertGuessedQuestion(question: GuessedQuestionEntity)

    @Query("DELETE FROM guessed_questions WHERE questionId = :questionId")
    suspend fun deleteGuessedQuestion(questionId: Long)

    @Query(
        """
        SELECT g.questionId FROM guessed_questions g
        INNER JOIN questions q ON q.id = g.questionId
        WHERE q.bankId = :bankId
        ORDER BY g.markedAt DESC
        """,
    )
    suspend fun getGuessedQuestionIds(bankId: String): List<Long>

    @Query(
        """
        SELECT COUNT(*) FROM guessed_questions g
        INNER JOIN questions q ON q.id = g.questionId
        WHERE q.bankId = :bankId
        """,
    )
    suspend fun getGuessedQuestionCount(bankId: String): Int
}

@Database(
    entities = [
        FolderEntity::class,
        QuestionBankEntity::class,
        QuestionEntity::class,
        OptionEntity::class,
        PracticeSessionEntity::class,
        SessionQuestionEntity::class,
        PracticeAnswerEntity::class,
        GuessedQuestionEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class SquizDatabase : RoomDatabase() {
    abstract fun dao(): SquizDao

    companion object {
        @Volatile private var instance: SquizDatabase? = null

        fun get(context: Context): SquizDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SquizDatabase::class.java,
                "squiz.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `practice_sessions` (`id` TEXT NOT NULL, `bankId` TEXT NOT NULL, `mode` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER, `currentPosition` INTEGER NOT NULL, `totalQuestions` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`bankId`) REFERENCES `question_banks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_sessions_bankId` ON `practice_sessions` (`bankId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_sessions_bankId_completedAt` ON `practice_sessions` (`bankId`, `completedAt`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `session_questions` (`sessionId` TEXT NOT NULL, `questionId` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `questionId`), FOREIGN KEY(`sessionId`) REFERENCES `practice_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_questions_questionId` ON `session_questions` (`questionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_session_questions_sessionId_orderIndex` ON `session_questions` (`sessionId`, `orderIndex`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `practice_answers` (`sessionId` TEXT NOT NULL, `questionId` INTEGER NOT NULL, `selectedLabels` TEXT NOT NULL, `isCorrect` INTEGER NOT NULL, `answeredAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `questionId`), FOREIGN KEY(`sessionId`) REFERENCES `practice_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_answers_questionId` ON `practice_answers` (`questionId`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `guessed_questions` (`questionId` INTEGER NOT NULL, `markedAt` INTEGER NOT NULL, PRIMARY KEY(`questionId`), FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL(
                    """UPDATE `practice_sessions` SET `completedAt` = COALESCE((SELECT MAX(`answeredAt`) FROM `practice_answers` WHERE `sessionId` = `practice_sessions`.`id`), `startedAt`), `currentPosition` = `totalQuestions` - 1 WHERE `completedAt` IS NULL AND (SELECT COUNT(*) FROM `practice_answers` WHERE `sessionId` = `practice_sessions`.`id`) >= `totalQuestions`""",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `guessed_questions_new` (`questionId` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, `markedAt` INTEGER NOT NULL, PRIMARY KEY(`questionId`), FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`sessionId`) REFERENCES `practice_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL(
                    """
                    INSERT INTO `guessed_questions_new` (`questionId`, `sessionId`, `markedAt`)
                    SELECT g.`questionId`,
                           (SELECT a.`sessionId`
                            FROM `practice_answers` a
                            WHERE a.`questionId` = g.`questionId`
                            ORDER BY a.`answeredAt` DESC
                            LIMIT 1),
                           g.`markedAt`
                    FROM `guessed_questions` g
                    WHERE EXISTS (
                        SELECT 1 FROM `practice_answers` a
                        WHERE a.`questionId` = g.`questionId`
                    )
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `guessed_questions`")
                db.execSQL("ALTER TABLE `guessed_questions_new` RENAME TO `guessed_questions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_guessed_questions_sessionId` ON `guessed_questions` (`sessionId`)")
            }
        }
    }
}
