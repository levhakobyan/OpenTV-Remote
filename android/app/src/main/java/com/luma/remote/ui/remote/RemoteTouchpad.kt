package com.luma.remote.ui.remote

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.RemoteViewModel
import com.luma.remote.control.SwipeNavigator
import com.luma.remote.protocol.*
import com.luma.remote.ui.*

/** Touchpad owns gestures; the transport remains capability-gated in the ViewModel. */
@Composable internal fun GesturePad(vm:RemoteViewModel,deviceId:String,caps:Capabilities,modifier:Modifier=Modifier) {
    val strings=LocalContext.current.resources

    val drawAccent=Accent;val drawMuted=Muted
    val haptic=LocalHapticFeedback.current
    val density=LocalDensity.current
    val firstStep=with(density){18.dp.toPx()}
    val repeatStep=with(density){32.dp.toPx()}
    var contact by remember(deviceId) { mutableStateOf<Offset?>(null) }
    var direction by remember(deviceId) { mutableStateOf<Command?>(null) }
    fun feedback() { if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    BoxWithConstraints(modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(28.dp)).background(Panel).semantics {
        role=Role.Button
        contentDescription=if(caps.pointer)strings.getString(R.string.ui_tv_touchpad_move_to_point_tap_to_select) else strings.getString(R.string.ui_touchpad_swipe_to_navigate_tap_anywhere_to_select)
        if(caps.pointer || Command.OK in caps.commands)onClick(strings.getString(R.string.ui_select)) { feedback();if(caps.pointer)vm.click() else vm.send(Command.OK);true }
        customActions=listOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT).filter { it in caps.commands }.map { command -> CustomAccessibilityAction(strings.label(command)) { feedback();vm.send(command);true } }
    }.pointerInput(vm,deviceId,caps,firstStep,repeatStep) {
        try {
            awaitEachGesture {
                val down=awaitFirstDown();down.consume()
                val navigator=SwipeNavigator(viewConfiguration.touchSlop,firstStep,repeatStep)
                val token=Any()
                vm.startMotion(token)
                var cancelled=false
                var scrolling=false
                var wheel=0f
                contact=down.position;direction=null
                try {
                    do {
                        val event=awaitPointerEvent(PointerEventPass.Main)
                        val change=event.changes.firstOrNull { it.id==down.id }
                        if(caps.scroll && event.changes.size>=2) {
                            scrolling=true
                            val moving=event.changes.filter { it.pressed && it.previousPressed }
                            if(moving.size>=2) {
                                wheel-=moving.map { it.positionChange().y }.average().toFloat()/(24*density.density)
                                if(kotlin.math.abs(wheel)>=1f) { val ticks=wheel.toInt();vm.motion(token,MotionStep(scroll=ticks.toFloat()));wheel-=ticks }
                            }
                            event.changes.forEach { it.consume() }
                            if(event.changes.none { it.pressed })break
                            continue
                        }
                        if(scrolling) { event.changes.forEach { it.consume() };if(event.changes.none { it.pressed })break;continue }
                        if(change==null || event.changes.size>1 || change.isConsumed) { cancelled=true;break }
                        val delta=change.positionChange()
                        val step=navigator.move(delta.x,delta.y,change.uptimeMillis)
                        if(change.pressed) {
                            contact=change.position
                            if(navigator.dragged && caps.pointer)vm.motion(token,MotionStep(delta.x/density.density*1.5f,delta.y/density.density*1.5f))
                            else if(step!=null) { direction=step;vm.motion(token,MotionStep(key=step));feedback() }
                        }
                        change.consume()
                    } while(event.changes.any { it.pressed })
                    if(!navigator.dragged && !cancelled && !scrolling && (caps.pointer || Command.OK in caps.commands)) { feedback();if(caps.pointer)vm.click() else vm.send(Command.OK) }
                } finally { vm.stopMotion(token);contact=null;direction=null }
            }
        } finally { contact=null;direction=null }
    },contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val spacing=24.dp.toPx()
            var y=spacing
            while(y<size.height) { var x=spacing;while(x<size.width) { drawCircle(drawMuted.copy(alpha=.11f),1.dp.toPx(),Offset(x,y));x+=spacing };y+=spacing }
            contact?.let { point ->
                drawCircle(drawAccent.copy(alpha=.12f),30.dp.toPx(),point)
                drawCircle(drawAccent.copy(alpha=.5f),30.dp.toPx(),point,style=Stroke(1.dp.toPx()))
                drawCircle(drawAccent,4.dp.toPx(),point)
            }
        }
        if(!caps.pointer) {
            listOf(Triple(Command.UP,Alignment.TopCenter,Icons.Outlined.KeyboardArrowUp),Triple(Command.DOWN,Alignment.BottomCenter,Icons.Outlined.KeyboardArrowDown),Triple(Command.LEFT,Alignment.CenterStart,Icons.Outlined.KeyboardArrowLeft),Triple(Command.RIGHT,Alignment.CenterEnd,Icons.Outlined.KeyboardArrowRight)).forEach { (command,alignment,icon) ->
                Icon(icon,null,Modifier.align(alignment).padding(horizontal=12.dp,vertical=12.dp).size(24.dp),tint=if(direction==command)Accent else Muted.copy(alpha=.35f))
            }
        }
        Box(Modifier.size(64.dp).background(if(contact==null)Raised else Accent.copy(alpha=.14f),CircleShape),contentAlignment=Alignment.Center) { Text(strings.getString(R.string.ui_ok),fontSize=18.sp,color=Accent) }
        if(contact==null && maxHeight>=200.dp && density.fontScale<1.5f)Text(if(caps.pointer)if(caps.scroll)strings.getString(R.string.ui_move_tap_two_fingers_to_scroll) else strings.getString(R.string.ui_move_tap_to_select) else strings.getString(R.string.ui_swipe_tap_anywhere),Modifier.offset(y=56.dp).padding(horizontal=32.dp),fontSize=12.sp,color=Muted)

    }
}
