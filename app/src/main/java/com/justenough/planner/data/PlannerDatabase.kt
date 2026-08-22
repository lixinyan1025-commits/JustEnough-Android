package com.justenough.planner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlanEntity::class, TimeBlockEntity::class, SchedulePlanCrossRef::class, TaskRunEntity::class,
        EnergyCheckInEntity::class, AnchorOccurrenceEntity::class, AiMessageEntity::class, DiagnosticEventEntity::class,
        TaskFeedbackEntity::class, PetPromptEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class PlannerDatabase : RoomDatabase() {
    abstract fun plannerDao(): PlannerDao

    companion object {
        @Volatile private var INSTANCE: PlannerDatabase? = null
        fun get(context: Context): PlannerDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, PlannerDatabase::class.java, "just-enough.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { INSTANCE = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `anchor_occurrences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `blockId` INTEGER NOT NULL, `actionId` INTEGER NOT NULL, `occurrenceEpochDay` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `handledAt` INTEGER, FOREIGN KEY(`actionId`) REFERENCES `actions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anchor_occurrences_actionId` ON `anchor_occurrences` (`actionId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_anchor_occurrences_blockId_actionId_occurrenceEpochDay` ON `anchor_occurrences` (`blockId`,`actionId`,`occurrenceEpochDay`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anchor_occurrences_status` ON `anchor_occurrences` (`status`)")
        } }
        private val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `anchor_occurrences_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `blockId` INTEGER NOT NULL, `actionId` INTEGER NOT NULL, `occurrenceEpochDay` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `handledAt` INTEGER, FOREIGN KEY(`blockId`) REFERENCES `time_blocks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`actionId`) REFERENCES `actions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `anchor_occurrences_new` SELECT occurrence.* FROM `anchor_occurrences` occurrence INNER JOIN `time_blocks` block ON block.id=occurrence.blockId INNER JOIN `actions` action ON action.id=occurrence.actionId")
            db.execSQL("DROP TABLE `anchor_occurrences`"); db.execSQL("ALTER TABLE `anchor_occurrences_new` RENAME TO `anchor_occurrences`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anchor_occurrences_actionId` ON `anchor_occurrences` (`actionId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_anchor_occurrences_blockId_actionId_occurrenceEpochDay` ON `anchor_occurrences` (`blockId`,`actionId`,`occurrenceEpochDay`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anchor_occurrences_status` ON `anchor_occurrences` (`status`)")
        } }
        private val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE `task_runs` ADD COLUMN `fulfillmentPoints` INTEGER") } }

        val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE `plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `minimumGoal` TEXT NOT NULL, `estimatedMinutes` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `appPackage` TEXT, `appClass` TEXT, `lastChosenEpochMillis` INTEGER, `reviewState` TEXT NOT NULL, `scheduledEpochDay` INTEGER, `dismissedEpochDay` INTEGER)")
            db.execSQL("INSERT INTO `plans` (`id`,`name`,`minimumGoal`,`estimatedMinutes`,`enabled`,`archived`,`appPackage`,`appClass`,`lastChosenEpochMillis`,`reviewState`,`scheduledEpochDay`,`dismissedEpochDay`) SELECT `id`,`name`,`minimumGoal`,`estimatedMinutes`,`enabled`,0,`appPackage`,`appClass`,`lastChosenEpochMillis`,`reviewState`,`scheduledEpochDay`,`dismissedEpochDay` FROM `actions`")
            db.execSQL("CREATE INDEX `index_plans_archived` ON `plans` (`archived`)"); db.execSQL("CREATE INDEX `index_plans_enabled` ON `plans` (`enabled`)")
            db.execSQL("CREATE TABLE `schedule_plans` (`blockId` INTEGER NOT NULL, `planId` INTEGER NOT NULL, PRIMARY KEY(`blockId`,`planId`), FOREIGN KEY(`blockId`) REFERENCES `time_blocks`(`id`) ON DELETE CASCADE, FOREIGN KEY(`planId`) REFERENCES `plans`(`id`) ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `schedule_plans` SELECT `blockId`,`actionId` FROM `block_actions`")
            db.execSQL("CREATE INDEX `index_schedule_plans_blockId` ON `schedule_plans` (`blockId`)"); db.execSQL("CREATE INDEX `index_schedule_plans_planId` ON `schedule_plans` (`planId`)")
            db.execSQL("CREATE TABLE `task_runs_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `pausedAt` INTEGER, `pausedDurationMillis` INTEGER NOT NULL, `plannedMinutes` INTEGER NOT NULL, `status` TEXT NOT NULL, `actualLoad` INTEGER, `fulfillmentPoints` INTEGER, FOREIGN KEY(`planId`) REFERENCES `plans`(`id`) ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `task_runs_new` SELECT `id`,`actionId`,`startedAt`,`endedAt`,`pausedAt`,`pausedDurationMillis`,`plannedMinutes`,`status`,`actualLoad`,`fulfillmentPoints` FROM `task_runs`")
            db.execSQL("DROP TABLE `task_runs`"); db.execSQL("ALTER TABLE `task_runs_new` RENAME TO `task_runs`"); db.execSQL("CREATE INDEX `index_task_runs_planId` ON `task_runs` (`planId`)"); db.execSQL("CREATE INDEX `index_task_runs_status` ON `task_runs` (`status`)")
            db.execSQL("CREATE TABLE `anchor_occurrences_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `blockId` INTEGER NOT NULL, `planId` INTEGER NOT NULL, `occurrenceEpochDay` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `handledAt` INTEGER, FOREIGN KEY(`blockId`) REFERENCES `time_blocks`(`id`) ON DELETE CASCADE, FOREIGN KEY(`planId`) REFERENCES `plans`(`id`) ON DELETE CASCADE)")
            db.execSQL("INSERT INTO `anchor_occurrences_new` SELECT `id`,`blockId`,`actionId`,`occurrenceEpochDay`,`scheduledAt`,`status`,`handledAt` FROM `anchor_occurrences`")
            db.execSQL("DROP TABLE `anchor_occurrences`"); db.execSQL("ALTER TABLE `anchor_occurrences_new` RENAME TO `anchor_occurrences`"); db.execSQL("CREATE INDEX `index_anchor_occurrences_planId` ON `anchor_occurrences` (`planId`)"); db.execSQL("CREATE UNIQUE INDEX `index_anchor_occurrences_blockId_planId_occurrenceEpochDay` ON `anchor_occurrences` (`blockId`,`planId`,`occurrenceEpochDay`)"); db.execSQL("CREATE INDEX `index_anchor_occurrences_status` ON `anchor_occurrences` (`status`)")
            db.execSQL("CREATE TABLE `ai_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `kind` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL)"); db.execSQL("CREATE INDEX `index_ai_messages_createdAt` ON `ai_messages` (`createdAt`)"); db.execSQL("CREATE INDEX `index_ai_messages_kind` ON `ai_messages` (`kind`)")
            db.execSQL("CREATE TABLE `diagnostic_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `manufacturer` TEXT NOT NULL, `androidVersion` TEXT NOT NULL, `detail` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"); db.execSQL("CREATE INDEX `index_diagnostic_events_createdAt` ON `diagnostic_events` (`createdAt`)"); db.execSQL("CREATE INDEX `index_diagnostic_events_type` ON `diagnostic_events` (`type`)")
            db.execSQL("DROP TABLE `block_actions`"); db.execSQL("DROP TABLE `actions`"); db.execSQL("DROP TABLE `projects`"); db.execSQL("DROP TABLE `areas`")
        } }
        val MIGRATION_5_6 = object : Migration(5, 6) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `plans` ADD COLUMN `quadrant` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `plans` ADD COLUMN `matrixOrder` INTEGER NOT NULL DEFAULT 0")
        } }
        val MIGRATION_6_7 = object : Migration(6, 7) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `time_blocks` ADD COLUMN `startSoundEnabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE TABLE IF NOT EXISTS `task_feedback` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `runId` INTEGER, `kind` TEXT NOT NULL, `score` INTEGER, `answerCode` TEXT, `answerText` TEXT, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`planId`) REFERENCES `plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`runId`) REFERENCES `task_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_feedback_planId` ON `task_feedback` (`planId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_feedback_runId` ON `task_feedback` (`runId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_feedback_createdAt` ON `task_feedback` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_feedback_kind` ON `task_feedback` (`kind`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `pet_prompts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `options` TEXT NOT NULL, `status` TEXT NOT NULL, `priority` INTEGER NOT NULL, `planId` INTEGER, `runId` INTEGER, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `expiresAt` INTEGER, `answeredAt` INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_prompts_status` ON `pet_prompts` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_prompts_createdAt` ON `pet_prompts` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_prompts_priority` ON `pet_prompts` (`priority`)")
        } }
        val MIGRATION_7_8 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `time_blocks` ADD COLUMN `autoStartEnabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `time_blocks` ADD COLUMN `autoOpenAppEnabled` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `time_blocks` ADD COLUMN `vibrateEnabled` INTEGER NOT NULL DEFAULT 1")
        } }
    }
}
