package com.luma.remote.ui.remote.controls

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*
import com.luma.remote.ui.*

@Composable internal fun RepeatingKey(vm:RemoteViewModel,command:Command,modifier:Modifier,shape:Shape=CircleShape,prominent:Boolean=false,content:@Composable ()->Unit) {
    val strings=LocalContext.current.resources

    val haptic=LocalHapticFeedback.current
    val lifecycle=LocalLifecycleOwner.current
    val keyToken=remember { Any() }
    val touchToken=remember { Any() }
    fun feedback() { if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    DisposableEffect(vm,lifecycle,command) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE) { vm.stopHold(keyToken);vm.stopHold(touchToken) } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);vm.stopHold(keyToken);vm.stopHold(touchToken) }
    }
    Surface(modifier.semantics {
        role=Role.Button; contentDescription=strings.label(command)
        onClick { feedback();vm.send(command);true }
    }.onKeyEvent { event ->
        if(event.key in setOf(Key.Enter,Key.NumPadEnter,Key.DirectionCenter,Key.Spacebar)) {
            if(event.type==KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount==0) { feedback();vm.startHold(keyToken,command) }
            if(event.type==KeyEventType.KeyUp)vm.stopHold(keyToken)
            true
        } else false
    }.onFocusChanged { if(!it.isFocused)vm.stopHold(keyToken) }.focusable().pointerInput(vm,command) {
        detectTapGestures(onPress={
            feedback();vm.startHold(touchToken,command)
            try { tryAwaitRelease() } finally { vm.stopHold(touchToken) }
        })
    },shape=shape,color=if(prominent)Accent else Panel,contentColor=if(prominent)Ink else MaterialTheme.colorScheme.onSurface) { Box(contentAlignment=Alignment.Center) { content() } }
}

@Composable internal fun Dpad(vm:RemoteViewModel,caps:Capabilities,size:Dp,target:Dp=48.dp) {
    val strings=LocalContext.current.resources

    if(caps.commands.none { it in setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK) })return
    Box(Modifier.size(size).clip(CircleShape).background(Brush.radialGradient(listOf(Raised,Panel))).border(1.dp,MaterialTheme.colorScheme.outline,CircleShape),contentAlignment=Alignment.Center) {
        val directions=listOf(Triple(Command.UP,Icons.Outlined.KeyboardArrowUp,Alignment.TopCenter),Triple(Command.DOWN,Icons.Outlined.KeyboardArrowDown,Alignment.BottomCenter),Triple(Command.LEFT,Icons.Outlined.KeyboardArrowLeft,Alignment.CenterStart),Triple(Command.RIGHT,Icons.Outlined.KeyboardArrowRight,Alignment.CenterEnd))
        directions.forEach { (command,icon,alignment) -> if(command in caps.commands) {
            RepeatingKey(vm,command,Modifier.align(alignment).padding(4.dp).size(target)) { Icon(icon,null,Modifier.size(30.dp),tint=MaterialTheme.colorScheme.onSurface) }
        } }
        if(Command.OK in caps.commands) {
            RepeatingKey(vm,Command.OK,Modifier.size(if(size<200.dp)72.dp else 80.dp),prominent=true) { Text(strings.getString(R.string.ui_ok),fontSize=21.sp,fontWeight=FontWeight.Bold) }
        }
    }
}
@Composable internal fun RemoteCommand(vm:RemoteViewModel,command:Command,modifier:Modifier=Modifier,joined:Boolean=false) {
    val strings=LocalContext.current.resources

    val haptic=LocalHapticFeedback.current
    val digit=command in AndroidTvProtocol.digits
    val icon=commandIcon(command)
    @Composable fun Content() { BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        val availableHeight=maxHeight
        val availableWidth=maxWidth
        val large=availableWidth>=104.dp && availableHeight>=104.dp
        val glyph=minOf(40.dp,minOf(availableWidth,availableHeight)*.4f).coerceAtLeast(20.dp)
        val caption=!digit && availableHeight>=54.dp && (LocalDensity.current.fontScale<1.5f || availableHeight>=100.dp)
        Column(Modifier.padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(digit) { val size=minOf(if(large)40f else 24f,(availableHeight.value-8)/(1.2f*LocalDensity.current.fontScale)).coerceAtLeast(12f);Text(strings.label(command),fontSize=size.sp,lineHeight=(size*1.2f).sp,maxLines=1) } else Icon(icon,null,Modifier.size(glyph))
            val shortLabel=when(command) {
                Command.VOLUME_UP -> strings.getString(R.string.ui_vol_184);Command.VOLUME_DOWN -> strings.getString(R.string.ui_vol_185)
                Command.CHANNEL_UP -> strings.getString(R.string.ui_ch_186);Command.CHANNEL_DOWN -> strings.getString(R.string.ui_ch_187)
                else -> strings.label(command)
            }
            if(caption)Text(shortLabel,fontSize=if(large)14.sp else 10.sp,lineHeight=if(large)18.sp else 12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    } }
    if(command in pressCommands)RepeatingKey(vm,command,modifier,RoundedCornerShape(if(joined)0.dp else 16.dp),prominent=command==Command.OK) { Content() }
    else FilledTonalButton(onClick={if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);vm.send(command)},modifier=modifier.semantics { contentDescription=strings.label(command) },contentPadding=PaddingValues(0.dp),shape=RoundedCornerShape(if(joined)0.dp else 16.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(command==Command.OK || command==Command.PLAY_PAUSE)Accent else Panel,contentColor=if(command==Command.OK || command==Command.PLAY_PAUSE)Ink else MaterialTheme.colorScheme.onSurface)) { Content() }
}
internal fun commandIcon(command:Command):ImageVector=when(command) {
        Command.UP -> Icons.Outlined.KeyboardArrowUp;Command.DOWN -> Icons.Outlined.KeyboardArrowDown
        Command.LEFT -> Icons.Outlined.KeyboardArrowLeft;Command.RIGHT -> Icons.Outlined.KeyboardArrowRight
        Command.OK -> Icons.Outlined.Check;Command.BACK -> Icons.Outlined.KeyboardReturn;Command.HOME -> Icons.Outlined.Home
        Command.VOLUME_UP -> Icons.Outlined.VolumeUp;Command.VOLUME_DOWN -> Icons.Outlined.VolumeDown;Command.MUTE -> Icons.Outlined.VolumeOff
        Command.REWIND -> Icons.Outlined.FastRewind;Command.FORWARD -> Icons.Outlined.FastForward;Command.PLAY_PAUSE -> Icons.Outlined.PlayArrow
        Command.POWER -> Icons.Outlined.PowerSettingsNew;Command.TV -> Icons.Outlined.Tv;Command.DELETE -> Icons.Outlined.Backspace
        Command.INPUT -> Icons.Outlined.Input;Command.CHANNEL_UP -> Icons.Outlined.KeyboardArrowUp;Command.CHANNEL_DOWN -> Icons.Outlined.KeyboardArrowDown
        Command.GUIDE -> Icons.Outlined.ViewList;Command.APPS -> Icons.Outlined.Apps;Command.INFO -> Icons.Outlined.Info;Command.MENU -> Icons.Outlined.Menu
        else -> Icons.Outlined.Settings
    }
