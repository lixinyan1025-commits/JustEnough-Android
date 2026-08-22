package com.justenough.planner.task

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

object TaskSoundPlayer {
    const val NONE="NONE";const val LEAF="LEAF";const val BELL="BELL";const val WOOD="WOOD";const val WARM="WARM";const val LIGHT="LIGHT";const val CLEAR="CLEAR"
    val startChoices=listOf(NONE to "静音",LEAF to "叶响",BELL to "轻铃",WOOD to "木音")
    val completionChoices=listOf(NONE to "静音",WARM to "暖光",LIGHT to "轻落",CLEAR to "清响")

    fun play(id:String){
        if(id==NONE)return
        val tone=ToneGenerator(AudioManager.STREAM_NOTIFICATION,62)
        val sequence=when(id){
            BELL->listOf(ToneGenerator.TONE_PROP_BEEP to 140, ToneGenerator.TONE_PROP_ACK to 150)
            WOOD->listOf(ToneGenerator.TONE_PROP_PROMPT to 100)
            LIGHT->listOf(ToneGenerator.TONE_PROP_ACK to 110)
            CLEAR->listOf(ToneGenerator.TONE_PROP_BEEP to 90,ToneGenerator.TONE_PROP_BEEP to 90)
            WARM->listOf(ToneGenerator.TONE_PROP_ACK to 150,ToneGenerator.TONE_PROP_PROMPT to 120)
            else->listOf(ToneGenerator.TONE_PROP_PROMPT to 130)
        }
        val handler=Handler(Looper.getMainLooper());var offset=0L
        sequence.forEach{(kind,duration)->handler.postDelayed({runCatching{tone.startTone(kind,duration)}},offset);offset+=duration+75L}
        handler.postDelayed({runCatching{tone.release()}},offset+200)
    }
}
