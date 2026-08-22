package com.justenough.planner.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationDeviceTest {
    @Test fun migration7To8AddsAutoStartControlsWithoutLosingBlocks(){
        val context=ApplicationProvider.getApplicationContext<Context>();val name="migration-v8-${System.nanoTime()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(7){
            override fun onCreate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE time_blocks(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,kind TEXT NOT NULL,startMinute INTEGER NOT NULL,endMinute INTEGER NOT NULL,weekdayMask INTEGER NOT NULL,dateEpochDay INTEGER,reminderEnabled INTEGER NOT NULL,enabled INTEGER NOT NULL,startSoundEnabled INTEGER NOT NULL)")
                db.execSQL("INSERT INTO time_blocks VALUES(5,'晨读','ANCHOR',420,480,127,NULL,1,1,1)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        try{val db=helper.writableDatabase;PlannerDatabase.MIGRATION_7_8.migrate(db);db.query("SELECT id,startSoundEnabled,autoStartEnabled,autoOpenAppEnabled,vibrateEnabled FROM time_blocks").use{it.moveToFirst();assertEquals(5L,it.getLong(0));assertEquals(1,it.getInt(1));assertEquals(1,it.getInt(2));assertEquals(0,it.getInt(3));assertEquals(1,it.getInt(4))}}finally{helper.close();context.deleteDatabase(name)}
    }
    @Test fun migration6To7AddsSoundAndPetFeedbackWithoutLosingSchedules(){
        val context=ApplicationProvider.getApplicationContext<Context>();val name="migration-v7-${System.nanoTime()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(6){
            override fun onCreate(db:SupportSQLiteDatabase){
                db.execSQL("PRAGMA foreign_keys=ON")
                db.execSQL("CREATE TABLE plans(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,minimumGoal TEXT NOT NULL,estimatedMinutes INTEGER NOT NULL,enabled INTEGER NOT NULL,archived INTEGER NOT NULL,appPackage TEXT,appClass TEXT,lastChosenEpochMillis INTEGER,reviewState TEXT NOT NULL,scheduledEpochDay INTEGER,dismissedEpochDay INTEGER,quadrant TEXT NOT NULL,matrixOrder INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE task_runs(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,planId INTEGER NOT NULL,startedAt INTEGER NOT NULL,endedAt INTEGER,pausedAt INTEGER,pausedDurationMillis INTEGER NOT NULL,plannedMinutes INTEGER NOT NULL,status TEXT NOT NULL,actualLoad INTEGER,fulfillmentPoints INTEGER)")
                db.execSQL("CREATE TABLE time_blocks(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,kind TEXT NOT NULL,startMinute INTEGER NOT NULL,endMinute INTEGER NOT NULL,weekdayMask INTEGER NOT NULL,dateEpochDay INTEGER,reminderEnabled INTEGER NOT NULL,enabled INTEGER NOT NULL)")
                db.execSQL("INSERT INTO time_blocks VALUES(3,'上午','CHOICE',480,600,127,NULL,1,1)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        try{val db=helper.writableDatabase;PlannerDatabase.MIGRATION_6_7.migrate(db);db.query("SELECT id,startSoundEnabled FROM time_blocks").use{it.moveToFirst();assertEquals(3L,it.getLong(0));assertEquals(1,it.getInt(1))};db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('task_feedback','pet_prompts')").use{var count=0;while(it.moveToNext())count++;assertEquals(2,count)}}finally{helper.close();context.deleteDatabase(name)}
    }
    @Test fun migration5To6PreservesPlansAndMarksThemForClassification(){
        val context=ApplicationProvider.getApplicationContext<Context>();val name="migration-v6-${System.nanoTime()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(5){
            override fun onCreate(db:SupportSQLiteDatabase){db.execSQL("CREATE TABLE plans(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,minimumGoal TEXT NOT NULL,estimatedMinutes INTEGER NOT NULL,enabled INTEGER NOT NULL,archived INTEGER NOT NULL,appPackage TEXT,appClass TEXT,lastChosenEpochMillis INTEGER,reviewState TEXT NOT NULL,scheduledEpochDay INTEGER,dismissedEpochDay INTEGER)");db.execSQL("INSERT INTO plans VALUES(7,'旧计划','旧目标',20,1,0,NULL,NULL,NULL,'NONE',NULL,NULL)")}
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        try{val db=helper.writableDatabase;PlannerDatabase.MIGRATION_5_6.migrate(db);db.query("SELECT id,name,quadrant,matrixOrder FROM plans").use{it.moveToFirst();assertEquals(7L,it.getLong(0));assertEquals("旧计划",it.getString(1));assertEquals("",it.getString(2));assertEquals(0,it.getInt(3))}}finally{helper.close();context.deleteDatabase(name)}
    }
    @Test fun migration4To5PreservesDuplicateNamesSchedulesAndHistory() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-${System.nanoTime()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(4){
            override fun onCreate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE areas(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,sortOrder INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,areaId INTEGER NOT NULL,name TEXT NOT NULL,sortOrder INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE actions(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,projectId INTEGER NOT NULL,name TEXT NOT NULL,minimumGoal TEXT NOT NULL,estimatedMinutes INTEGER NOT NULL,estimatedLoad INTEGER NOT NULL,enabled INTEGER NOT NULL,appPackage TEXT,appClass TEXT,lastChosenEpochMillis INTEGER,reviewState TEXT NOT NULL,scheduledEpochDay INTEGER,dismissedEpochDay INTEGER)")
                db.execSQL("CREATE TABLE time_blocks(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,kind TEXT NOT NULL,startMinute INTEGER NOT NULL,endMinute INTEGER NOT NULL,weekdayMask INTEGER NOT NULL,dateEpochDay INTEGER,reminderEnabled INTEGER NOT NULL,enabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE block_actions(blockId INTEGER NOT NULL,actionId INTEGER NOT NULL,PRIMARY KEY(blockId,actionId))")
                db.execSQL("CREATE TABLE task_runs(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,actionId INTEGER NOT NULL,startedAt INTEGER NOT NULL,endedAt INTEGER,pausedAt INTEGER,pausedDurationMillis INTEGER NOT NULL,plannedMinutes INTEGER NOT NULL,status TEXT NOT NULL,actualLoad INTEGER,fulfillmentPoints INTEGER)")
                db.execSQL("CREATE TABLE energy_checkins(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,recordedAt INTEGER NOT NULL,level INTEGER NOT NULL,blockId INTEGER)")
                db.execSQL("CREATE TABLE anchor_occurrences(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,blockId INTEGER NOT NULL,actionId INTEGER NOT NULL,occurrenceEpochDay INTEGER NOT NULL,scheduledAt INTEGER NOT NULL,status TEXT NOT NULL,handledAt INTEGER)")
                db.execSQL("INSERT INTO areas VALUES(1,'旧领域',0)");db.execSQL("INSERT INTO projects VALUES(1,1,'旧项目',0)")
                db.execSQL("INSERT INTO actions VALUES(7,1,'同名计划','目标甲',20,2,1,'pkg.a','A',NULL,'NONE',NULL,NULL)")
                db.execSQL("INSERT INTO actions VALUES(8,1,'同名计划','目标乙',30,3,1,NULL,NULL,NULL,'NONE',NULL,NULL)")
                db.execSQL("INSERT INTO time_blocks VALUES(3,'早晨','CHOICE',360,420,127,NULL,1,1)")
                db.execSQL("INSERT INTO block_actions VALUES(3,7)");db.execSQL("INSERT INTO block_actions VALUES(3,8)")
                db.execSQL("INSERT INTO task_runs VALUES(11,7,1000,2000,NULL,0,20,'COMPLETED',2,4)")
                db.execSQL("INSERT INTO anchor_occurrences VALUES(12,3,8,20000,3000,'PENDING',NULL)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        try{
            val db=helper.writableDatabase
            PlannerDatabase.MIGRATION_4_5.migrate(db)
            db.query("SELECT id,name,minimumGoal FROM plans ORDER BY id").use{cursor->
                val rows=mutableListOf<Triple<Long,String,String>>();while(cursor.moveToNext())rows+=Triple(cursor.getLong(0),cursor.getString(1),cursor.getString(2))
                assertEquals(listOf(Triple(7L,"同名计划","目标甲"),Triple(8L,"同名计划","目标乙")),rows)
            }
            db.query("SELECT blockId,planId FROM schedule_plans ORDER BY planId").use{cursor->val ids=mutableListOf<Long>();while(cursor.moveToNext()){assertEquals(3L,cursor.getLong(0));ids+=cursor.getLong(1)};assertEquals(listOf(7L,8L),ids)}
            db.query("SELECT id,planId,fulfillmentPoints FROM task_runs").use{it.moveToFirst();assertEquals(11L,it.getLong(0));assertEquals(7L,it.getLong(1));assertEquals(4,it.getInt(2))}
            db.query("SELECT id,planId FROM anchor_occurrences").use{it.moveToFirst();assertEquals(12L,it.getLong(0));assertEquals(8L,it.getLong(1))}
        }finally{helper.close();context.deleteDatabase(name)}
    }
}
