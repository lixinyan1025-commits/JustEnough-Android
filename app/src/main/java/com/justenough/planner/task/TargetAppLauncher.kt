package com.justenough.planner.task

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.justenough.planner.data.PlanEntity

object TargetAppLauncher {
    fun launch(context: Context, plan: PlanEntity): Result<Unit> = runCatching {
        val packageName = requireNotNull(plan.appPackage) { "这个计划还没有绑定应用" }
        val pm = context.packageManager
        val explicit = plan.appClass?.let { Intent.makeMainActivity(ComponentName(packageName, it)) }
        val packageLaunch = pm.getLaunchIntentForPackage(packageName)
        val discovered = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), 0).firstOrNull()?.activityInfo?.let { Intent.makeMainActivity(ComponentName(it.packageName, it.name)) }
        val target = listOfNotNull(explicit, packageLaunch, discovered).firstOrNull { it.resolveActivity(pm) != null } ?: error("绑定应用已卸载或没有可用入口，请重新绑定")
        context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }
}
