package com.justenough.planner.pet

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.justenough.planner.R
import com.justenough.planner.appContainer
import com.justenough.planner.data.PetVisibility
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetOverlayDeviceTest {
    @Test fun characterIsVectorOnlyAndRepeatedShowNeverCreatesAnotherService() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val raw=context.resources.openRawResource(R.raw.xiaoman_character).bufferedReader().use{it.readText()}
        assertTrue(raw.contains("\"assets\":[]"))
        assertFalse(raw.contains("\"ty\":2"))

        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        context.appContainer.settings.update{it.copy(aiConnectionVerified=true,petVisibility=PetVisibility.VISIBLE,petEnabled=true)}
        AiPetService.show(context);AiPetService.show(context);AiPetService.trigger(context,AiPetService.ACTION_REMIND,"还有5分钟，阅读就要开始了哦。")
        delay(4000)
        val manager=context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION") val count=manager.getRunningServices(100).count{it.service.className==AiPetService::class.java.name}
        assertEquals(1,count)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/xiaoman-preview.png").use{FileInputStream(it.fileDescriptor).readBytes()}
        delay(300)

        AiPetService.disable(context);delay(900)
        @Suppress("DEPRECATION") val after=manager.getRunningServices(100).any{it.service.className==AiPetService::class.java.name}
        assertFalse(after)
    }
}
