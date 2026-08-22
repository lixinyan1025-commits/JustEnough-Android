package com.justenough.planner

import android.Manifest
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import com.justenough.planner.pet.AiPetService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.justenough.planner.reminder.NotificationHelper
import com.justenough.planner.ui.AppViewModel
import com.justenough.planner.ui.TodayScreen
import com.justenough.planner.ui.JustEnoughTheme
import com.justenough.planner.ui.PlanScreen
import com.justenough.planner.ui.SettingsScreen
import com.justenough.planner.ui.AiAnalysisScreen
import com.justenough.planner.data.PetVisibility

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lifecycleScope.launch { appContainer.repository.recordDiagnostic(if(granted)"NOTIFICATION_PERMISSION_OK" else "NOTIFICATION_PERMISSION_DENIED",if(granted)"通知权限已开启" else "通知权限未开启") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannels(this)
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            setContent { JustEnoughTheme { androidx.compose.material3.Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) { Text("暂不支持此设备", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium); Text("刚刚好 v1.4 仅为竖屏 Android 手机设计。") } } } }
            return
        }
        val initial = when (intent.getStringExtra("screen")) { "permissions" -> 3; "review" -> 1; "ai" -> 2; else -> 1 }
        setContent { JustEnoughTheme { AppShell(viewModel, initial) } }
    }

    override fun onResume() {
        super.onResume()
        if (resources.configuration.smallestScreenWidthDp >= 600) return
        lifecycleScope.launch {
            val state=appContainer.settings.state.first()
            if(PetVisibility.isVisible(state.petVisibility)&&state.aiConnectionVerified) {
                if(Settings.canDrawOverlays(this@MainActivity)) startAiPet()
                else appContainer.settings.update{it.copy(petEnabled=false,petVisibility=PetVisibility.DISABLED)}
            }
        }
    }

    fun requestWidgetPin(): Boolean {
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, com.justenough.planner.widget.TodayWidgetReceiver::class.java)
        if (manager.getAppWidgetIds(provider).isNotEmpty()) return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        val callback = PendingIntent.getBroadcast(
            this,
            7101,
            Intent(this, com.justenough.planner.widget.WidgetPinResultReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return manager.requestPinAppWidget(provider, null, callback)
    }

    fun requestLargeWidgetPin(): Boolean {
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, com.justenough.planner.widget.LargeTodayWidgetProvider::class.java)
        if (manager.getAppWidgetIds(provider).isNotEmpty()) return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        val callback = PendingIntent.getBroadcast(this, 7102, Intent(this, com.justenough.planner.widget.WidgetPinResultReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return manager.requestPinAppWidget(provider, null, callback)
    }

    fun widgetCount(): Int = getSystemService(AppWidgetManager::class.java)
        .getAppWidgetIds(ComponentName(this, com.justenough.planner.widget.TodayWidgetReceiver::class.java)).size
    fun largeWidgetCount(): Int = getSystemService(AppWidgetManager::class.java)
        .getAppWidgetIds(ComponentName(this, com.justenough.planner.widget.LargeTodayWidgetProvider::class.java)).size

    fun openExactAlarmSettings() { if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName"))) }
    fun requestNotificationPermission() {
        if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else lifecycleScope.launch{appContainer.repository.recordDiagnostic("NOTIFICATION_PERMISSION_OK","此Android版本安装后默认允许通知")}
    }
    fun notificationsAllowed():Boolean = Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    fun exactAlarmsAllowed():Boolean = Build.VERSION.SDK_INT<31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun openNotificationSettings() = startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    fun openBatterySettings() = runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.getOrElse { openAppSettings() }
    fun openAutostartSettings() {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        val intent = candidates.asSequence().map { Intent().setComponent(it) }.firstOrNull { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
        if (intent != null) runCatching { startActivity(intent) }.getOrElse { openAppSettings() } else openAppSettings()
    }
    fun openDeviceGuideAction(action:String) = when(action) {
        com.justenough.planner.diagnostics.DeviceGuide.NOTIFICATION -> openNotificationSettings()
        com.justenough.planner.diagnostics.DeviceGuide.EXACT_ALARM -> openExactAlarmSettings()
        com.justenough.planner.diagnostics.DeviceGuide.BATTERY -> openBatterySettings()
        com.justenough.planner.diagnostics.DeviceGuide.AUTOSTART -> openAutostartSettings()
        com.justenough.planner.diagnostics.DeviceGuide.OVERLAY -> openOverlaySettings()
        com.justenough.planner.diagnostics.DeviceGuide.WIDGET -> requestLargeWidgetPin()
        else -> openAppSettings()
    }
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)
    fun openOverlaySettings() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    fun startAiPet() = AiPetService.show(this)
    fun stopAiPet() = AiPetService.hide(this)
    fun restartAiPet() = AiPetService.restart(this)
    fun testOverlay() {
        if (!canDrawOverlays()) { openOverlaySettings(); return }
        lifecycleScope.launch {
            val state=appContainer.settings.state.first()
            appContainer.repository.recordDiagnostic("OVERLAY_TEST_OK","悬浮窗权限有效")
            if(state.aiConnectionVerified) AiPetService.show(this@MainActivity)
        }
    }
}

@Composable
private fun AppShell(viewModel: AppViewModel, initial: Int) {
    var selected by remember { mutableIntStateOf(initial) }
    val items = listOf("计划" to Icons.AutoMirrored.Outlined.EventNote, "今天" to Icons.Outlined.Home, "AI分析" to Icons.Outlined.AutoAwesome, "设置" to Icons.Outlined.Settings)
    val state by viewModel.plannerState.collectAsStateWithLifecycle()
    val notice by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); viewModel.clearMessage() } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item -> NavigationBarItem(selected == index, { selected = index }, { Icon(item.second, item.first) }, label = { Text(item.first) }) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (selected) {
                0 -> PlanScreen(viewModel, padding)
                1 -> TodayScreen(viewModel, padding)
                2 -> AiAnalysisScreen(viewModel, padding)
                else -> SettingsScreen(viewModel, padding)
            }
        }
    }
}
