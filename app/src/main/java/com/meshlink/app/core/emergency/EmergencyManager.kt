package com.meshlink.app.core.emergency
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.media.ToneGenerator
import android.media.AudioManager
import kotlinx.coroutines.delay
class EmergencyManager(private val context:Context){
    suspend fun triggerLocal(){
        pulse(5, 220L, 280L)
    }

    /** Shorter receiver-side attention tone for a normal mesh alert. */
    suspend fun triggerAlertLocal(){
        pulse(2, 180L, 220L)
    }

    private suspend fun pulse(count:Int, toneMs:Long, gapMs:Long){
        val tone=ToneGenerator(AudioManager.STREAM_ALARM, 100)
        val vib=context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try {
            repeat(count){
                try{tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,toneMs.toInt())}catch(_:Throwable){}
                try{vib.vibrate(VibrationEffect.createOneShot(toneMs,VibrationEffect.DEFAULT_AMPLITUDE))}catch(_:Throwable){}
                delay(gapMs)
            }
        } finally {
            tone.release()
        }
    }
}
