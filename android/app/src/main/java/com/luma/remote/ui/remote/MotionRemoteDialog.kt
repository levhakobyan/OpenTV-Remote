package com.luma.remote.ui.remote

import com.luma.remote.R
import com.luma.remote.ui.*
import com.luma.remote.ui.remote.controls.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*

@Composable internal fun MotionRemoteDialog(vm:RemoteViewModel,state:RemoteState,dismiss:()->Unit) {
    val strings=LocalContext.current.resources

    val context=LocalContext.current
    val manager=remember { context.getSystemService(SensorManager::class.java) }
    val sensor=remember { manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    val gravity=remember { manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val rotation=LocalView.current.display?.rotation ?: 0
    val lifecycle=LocalLifecycleOwner.current
    var pointer by remember { mutableStateOf(state.capabilities.pointer) }
    var held by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(1f) }
    var error by remember { mutableStateOf<String?>(null) }
    val token=remember { Any() }
    val supported=if(pointer)state.capabilities.pointer else GestureNavigation.supported(state.capabilities)
    LaunchedEffect(supported) { if(!supported) { held=false;error=strings.getString(R.string.ui_this_motion_mode_is_unavailable_reconnect_to_restore_it) } }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE) { held=false;vm.stopMotion(token) } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);vm.stopMotion(token) }
    }
    DisposableEffect(held,pointer,sensitivity,rotation,state.phase,supported) {
        var listener:SensorEventListener?=null
        if(held && supported && sensor!=null && state.phase==Phase.READY && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val filter=MotionFilter(pointer,sensitivity)
            val l=object:SensorEventListener {
                override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
                override fun onSensorChanged(event:SensorEvent) {
                    if(event.sensor.type==Sensor.TYPE_GYROSCOPE)filter.sample(event.timestamp,event.values[0],event.values[1],rotation,event.values[2])?.let { vm.motion(token,it) }
                    else filter.gravity(event.values[0],event.values[1],event.values[2])
                }
            }
            vm.startMotion(token)
            if(manager.registerListener(l,sensor,20_000)) { listener=l;gravity?.let { manager.registerListener(l,it,20_000) } }
            else { vm.stopMotion(token);held=false;error=strings.getString(R.string.ui_the_phone_could_not_start_its_gyroscope) }
        }
        onDispose { listener?.let { manager.unregisterListener(it) };vm.stopMotion(token) }
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=560.dp).fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=Panel) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text(strings.getString(R.string.ui_motion_remote),Modifier.weight(1f),fontSize=22.sp);IconButton(onClick=dismiss) { Icon(Icons.Outlined.Close,strings.getString(R.string.ui_close_motion_remote)) } }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=pointer,onClick={pointer=true},enabled=state.capabilities.pointer && !held,label={Text(strings.getString(R.string.ui_pointer))},modifier=Modifier.heightIn(min=48.dp))
                    FilterChip(selected=!pointer,onClick={pointer=false},enabled=GestureNavigation.supported(state.capabilities) && !held,label={Text(strings.getString(R.string.ui_arrows))},modifier=Modifier.heightIn(min=48.dp))
                }
                Text(if(pointer)strings.getString(R.string.ui_relative_cursor_movement_hold_point_gently_release_to_reposition) else strings.getString(R.string.ui_phone_movement_sends_navigation_arrows_this_tv_connection_does_no),color=Muted,fontSize=13.sp)
                if(sensor==null)Text(strings.getString(R.string.ui_this_phone_has_no_gyroscope_use_the_d_pad_or_touch_controls),color=MaterialTheme.colorScheme.error)
                error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
                if(sensor!=null && supported)Box(Modifier.fillMaxWidth().height(104.dp).background(if(held)Accent else Raised,RoundedCornerShape(24.dp)).semantics {
                    role=Role.Button;contentDescription=if(held)strings.getString(R.string.ui_stop_motion_control) else strings.getString(R.string.ui_hold_to_move_accessibility_activate_toggles_motion)
                    onClick { held=!held;if(!held)vm.stopMotion(token);true }
                }.pointerInput(Unit) { detectTapGestures(onPress={held=true;try { tryAwaitRelease() } finally { held=false;vm.stopMotion(token) }}) },contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Outlined.ScreenRotation,null,tint=if(held)Ink else Accent);Text(if(held)strings.getString(R.string.ui_release_to_stop) else strings.getString(R.string.ui_hold_to_move),color=if(held)Ink else Accent,fontSize=18.sp) }
                }
                Text(strings.getString(R.string.ui_sensitivity),fontSize=13.sp,color=Muted)
                Slider(value=sensitivity,onValueChange={sensitivity=it},valueRange=.5f..2f,enabled=!held && sensor!=null,modifier=Modifier.semantics { contentDescription=strings.getString(R.string.ui_motion_sensitivity) })
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(Command.BACK in state.capabilities.commands)RemoteCommand(vm,Command.BACK,Modifier.weight(1f).height(64.dp))
                    if(pointer)FilledTonalButton(onClick=vm::click,modifier=Modifier.weight(1f).heightIn(min=64.dp)) { Text(strings.getString(R.string.ui_click)) }
                    else if(Command.OK in state.capabilities.commands)RemoteCommand(vm,Command.OK,Modifier.weight(1f).height(64.dp))
                    if(Command.HOME in state.capabilities.commands)RemoteCommand(vm,Command.HOME,Modifier.weight(1f).height(64.dp))
                }
                Text(strings.getString(R.string.ui_each_hold_starts_fresh_motion_stops_on_release_closing_this_panel),fontSize=12.sp,color=Muted)
            }
        }
    }
}
