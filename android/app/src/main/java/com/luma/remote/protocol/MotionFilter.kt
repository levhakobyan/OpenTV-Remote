package com.luma.remote.protocol

import kotlin.math.abs

data class MotionStep(val dx:Float=0f,val dy:Float=0f,val key:Command?=null,val scroll:Float=0f)
/** Gyroscope rates are rad/s in the phone's natural axes. Each clutch session starts from zero. */
class MotionFilter(private val pointer:Boolean,private val sensitivity:Float=1f) {
    private var previous=0L
    private var lastOutput=0L
    private var x=0f
    private var y=0f
    private var up:FloatArray?=null
    fun gravity(gx:Float,gy:Float,gz:Float) {
        val length=kotlin.math.sqrt(gx*gx+gy*gy+gz*gz)
        if(!length.isFinite() || length<.1f)return
        val next=floatArrayOf(gx/length,gy/length,gz/length)
        val old=up
        up=if(old==null)next else FloatArray(3) { old[it]*.8f+next[it]*.2f }
    }
    fun sample(time:Long,gx:Float,gy:Float,rotation:Int,gz:Float=0f):MotionStep? {
        if(!gx.isFinite() || !gy.isFinite() || !gz.isFinite())return null
        if(previous==0L || time<=previous || time-previous>200_000_000L) { previous=time;x=0f;y=0f;return null }
        val dt=(time-previous)/1_000_000_000f;previous=time
        val (fallbackYaw,pitch)=when(rotation) { 1 -> -gx to gy;2 -> -gy to -gx;3 -> gx to -gy;else -> gy to gx }
        val yaw=up?.let { gx*it[0]+gy*it[1]+gz*it[2] } ?: fallbackYaw
        val horizontal=if(abs(yaw)<.025f)0f else -yaw
        val vertical=if(abs(pitch)<.025f)0f else -pitch
        x+=horizontal*dt*sensitivity;y+=vertical*dt*sensitivity
        if(pointer) {
            if(time-lastOutput<33_000_000L)return null
            val dx=(x*850).coerceIn(-30f,30f);val dy=(y*850).coerceIn(-30f,30f)
            if(abs(dx)<1 && abs(dy)<1)return null
            x=0f;y=0f;lastOutput=time;return MotionStep(dx,dy)
        }
        if(horizontal==0f && vertical==0f) { x*=.85f;y*=.85f }
        if(maxOf(abs(x),abs(y))<.10f || time-lastOutput<180_000_000L)return null
        val key=GestureNavigation.direction(x,y);x=0f;y=0f;lastOutput=time
        return MotionStep(key=key)
    }
}
