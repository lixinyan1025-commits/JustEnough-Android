package com.justenough.planner.data

import android.content.Context
import com.justenough.planner.ai.AiPlannerClient
import com.justenough.planner.backup.BackupManager
import com.justenough.planner.reminder.ReminderScheduler
import com.justenough.planner.security.SecureKeyStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database = PlannerDatabase.get(appContext)
    val settings = AppSettings(appContext)
    val repository = PlannerRepository(database, settings)
    val secureKeyStore = SecureKeyStore(appContext)
    val scheduler = ReminderScheduler(appContext, database.plannerDao(), settings)
    val aiClient = AiPlannerClient(repository, settings, secureKeyStore)
    val backupManager = BackupManager(appContext, repository, settings)
}
