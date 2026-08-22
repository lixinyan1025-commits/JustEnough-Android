package com.justenough.planner.diagnostics

import android.os.Build

data class GuideStep(val title: String, val detail: String, val action: String)

object DeviceGuide {
    const val NOTIFICATION = "NOTIFICATION"
    const val EXACT_ALARM = "EXACT_ALARM"
    const val BATTERY = "BATTERY"
    const val AUTOSTART = "AUTOSTART"
    const val OVERLAY = "OVERLAY"
    const val WIDGET = "WIDGET"

    fun detectedBrand(): String {
        val value = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            "xiaomi" in value || "redmi" in value -> "XIAOMI"
            "huawei" in value -> "HUAWEI"
            "honor" in value -> "HONOR"
            "oppo" in value || "oneplus" in value || "realme" in value -> "OPPO"
            "vivo" in value || "iqoo" in value -> "VIVO"
            "samsung" in value -> "SAMSUNG"
            else -> "ANDROID"
        }
    }

    fun selected(override: String) = override.takeUnless { it == "AUTO" } ?: detectedBrand()
    fun displayName(value: String) = when (value) { "XIAOMI" -> "小米 / Redmi"; "HUAWEI" -> "华为 / 兼容APK的鸿蒙"; "HONOR" -> "荣耀"; "OPPO" -> "OPPO / 一加 / 真我"; "VIVO" -> "vivo / iQOO"; "SAMSUNG" -> "三星"; else -> "原生及其他Android" }
    fun guide(override: String) = "已按 ${displayName(selected(override))} 提供逐项入口。不同系统版本的名称可能略有差异，完成后请运行四项实际测试。"

    fun steps(override: String): List<GuideStep> {
        val brand = selected(override)
        val common = mutableListOf(
            GuideStep("1. 通知与锁屏显示", "允许通知、声音、振动和锁屏显示。", NOTIFICATION),
            GuideStep("2. 精确提醒", "允许闹钟与提醒，确保到点尽量准时。", EXACT_ALARM),
        )
        val brandSteps = when (brand) {
            "XIAOMI" -> listOf(GuideStep("3. 自启动", "手机管家 → 应用管理 → 权限 → 自启动，允许刚刚好。", AUTOSTART), GuideStep("4. 省电策略", "电量与性能 → 应用智能省电 → 刚刚好 → 无限制。", BATTERY))
            "HUAWEI" -> listOf(GuideStep("3. 应用启动管理", "手机管家 → 应用启动管理 → 刚刚好，关闭自动管理并允许三项手动管理。HarmonyOS NEXT不适用。", AUTOSTART), GuideStep("4. 电池优化", "设置 → 应用和服务 → 应用启动管理/电池优化，允许后台活动。", BATTERY))
            "HONOR" -> listOf(GuideStep("3. 应用启动管理", "设置 → 应用 → 应用启动管理 → 刚刚好，允许自启动、关联启动、后台活动。", AUTOSTART), GuideStep("4. 电池优化", "设置 → 电池 → 更多电池设置，允许后台运行。", BATTERY))
            "OPPO" -> listOf(GuideStep("3. 自启动", "设置 → 应用 → 自启动，允许刚刚好自动启动。", AUTOSTART), GuideStep("4. 后台活动", "设置 → 电池 → 应用耗电管理 → 刚刚好，允许后台活动。", BATTERY))
            "VIVO" -> listOf(GuideStep("3. 自启动", "i管家 → 应用管理 → 权限管理 → 自启动，允许刚刚好。", AUTOSTART), GuideStep("4. 后台高耗电", "设置 → 电池 → 后台耗电管理，允许刚刚好后台高耗电。", BATTERY))
            "SAMSUNG" -> listOf(GuideStep("3. 后台使用限制", "设置 → 电池 → 后台使用限制，确认刚刚好不在休眠/深度休眠应用中。", BATTERY), GuideStep("4. 不受限制", "应用信息 → 电池 → 选择不受限制。", AUTOSTART))
            else -> listOf(GuideStep("3. 电池优化", "将刚刚好设为不优化或不受限制。", BATTERY), GuideStep("4. 后台启动", "若系统提供自启动/后台活动选项，请允许。", AUTOSTART))
        }
        common += brandSteps
        common += GuideStep("5. 悬浮窗", "仅在使用小满时需要；可随时暂时隐藏或彻底关闭。", OVERLAY)
        common += GuideStep("6. 桌面组件", "长按桌面空白处 → 小组件/卡片 → 刚刚好 → 今日计划·大。", WIDGET)
        return common
    }
}
