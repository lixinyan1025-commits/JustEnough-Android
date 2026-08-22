package com.justenough.planner

import android.content.*
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justenough.planner.ai.AiPlannerClient
import com.justenough.planner.backup.BackupManager
import com.justenough.planner.data.*
import com.justenough.planner.security.SecureKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityBoundaryDeviceTest {
    private val context:Context=ApplicationProvider.getApplicationContext()

    @Test fun aiNetworkFailureNeverMutatesPlan()=runBlocking{
        val db=Room.inMemoryDatabaseBuilder(context,PlannerDatabase::class.java).build();val settings=AppSettings(context);val original=settings.state.first();val keys=SecureKeyStore(context);val oldKey=keys.getApiKey()
        try{db.plannerDao().insertPlan(PlanEntity(name="不能丢失",minimumGoal="一点"));val repository=PlannerRepository(db,settings);val before=repository.snapshot();settings.update{it.copy(aiConsent=true,aiProvider="CUSTOM",aiBaseUrl="http://127.0.0.1:9/v1",aiModel="unreachable",fallbackEnabled=false)};keys.putApiKey("test-only-key-never-log");val result=AiPlannerClient(repository,settings,keys).propose("失败保护");Assert.assertTrue(result.isFailure);Assert.assertEquals(before,repository.snapshot())}finally{settings.update{original};if(oldKey==null)keys.clearApiKey()else keys.putApiKey(oldKey);db.close()}
    }

    @Test fun encryptedBackupRejectsWrongPasswordAndTampering()=runBlocking{
        val db=Room.inMemoryDatabaseBuilder(context,PlannerDatabase::class.java).build();val settings=AppSettings(context);val repository=PlannerRepository(db,settings);val resolver=context.contentResolver;val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,"just-enough-${System.nanoTime()}.jeplan");put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");put(MediaStore.Downloads.RELATIVE_PATH,"Download/JustEnoughTests")})!!
        try{repository.addPlan("私密计划","一点",20,PlanQuadrants.IMPORTANT_NOT_URGENT);val before=repository.snapshot();val manager=BackupManager(context,repository,settings);manager.export(uri,"correct-password".toCharArray());val encrypted=resolver.openInputStream(uri)!!.use{it.readBytes()};Assert.assertTrue(encrypted.copyOfRange(0,8).contentEquals("JEPLAN1\n".toByteArray()));Assert.assertFalse(String(encrypted,Charsets.ISO_8859_1).contains("私密计划"));Assert.assertTrue(runCatching{manager.restore(uri,"wrong-password".toCharArray())}.isFailure);Assert.assertEquals(before,repository.snapshot());val tampered=encrypted.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()};resolver.openOutputStream(uri,"w")!!.use{it.write(tampered)};Assert.assertTrue(runCatching{manager.restore(uri,"correct-password".toCharArray())}.isFailure);Assert.assertEquals(before,repository.snapshot())}finally{resolver.delete(uri,null,null);db.close()}
    }
}
