package com.luma.remote.control

import com.luma.remote.protocol.Command
import com.luma.remote.protocol.GestureNavigation
import kotlin.math.abs
import kotlin.math.hypot

/** One instance per finger contact. No timer or output queue survives release. Distances are pixels. */
class SwipeNavigator(private val slop:Float,private val firstStep:Float,private val repeatStep:Float) {
    private var totalX=0f
    private var totalY=0f
    private var x=0f
    private var y=0f
    private var last:Long?=null
    var dragged=false
        private set
    fun move(dx:Float,dy:Float,timeMs:Long):Command? {
        if(!dx.isFinite() || !dy.isFinite())return null
        totalX+=dx;totalY+=dy
        if(hypot(totalX,totalY)>slop)dragged=true
        x=(x+dx).coerceIn(-repeatStep*2,repeatStep*2)
        y=(y+dy).coerceIn(-repeatStep*2,repeatStep*2)
        val threshold=if(last==null)firstStep else repeatStep
        if(!dragged || maxOf(abs(x),abs(y))<threshold)return null
        val previous=last
        if(previous!=null && timeMs-previous<90)return null
        val command=GestureNavigation.direction(x,y)
        x=0f;y=0f;last=timeMs
        return command
    }
}
