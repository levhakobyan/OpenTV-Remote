package com.luma.remote.ui.remote

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*
import com.luma.remote.ui.Muted
import com.luma.remote.ui.Ink
import com.luma.remote.ui.remote.controls.*

/** One physical-remote layout, using the same command and press/hold paths as the canvas. */
@Composable internal fun AllControlsRemote(vm:RemoteViewModel,caps:Capabilities) {
    val strings=LocalContext.current.resources

    val hand by vm.hand.collectAsStateWithLifecycle()
    val left=if(hand==HandPreference.RIGHT)CanvasKind.VOLUME else CanvasKind.CHANNELS
    val right=if(hand==HandPreference.RIGHT)CanvasKind.CHANNELS else CanvasKind.VOLUME
    Surface(Modifier.fillMaxWidth(),color=Ink,shape=RoundedCornerShape(28.dp)) {
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(strings.getString(R.string.ui_all_controls),Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
            if(Command.POWER in caps.commands)RemoteCommand(vm,Command.POWER,Modifier.size(56.dp))
        }
        CommandRow(vm,caps,listOf(Command.INPUT,Command.SETTINGS,Command.APPS))
        val digits=AndroidTvProtocol.digits.drop(1).chunked(3)+listOf(listOf(Command.DELETE,Command.ZERO,Command.TV))
        if(AndroidTvProtocol.digits.any { it in caps.commands }) {
            digits.forEach { CommandRow(vm,caps,it,keepSlots=true) }
        } else CommandRow(vm,caps,listOf(Command.DELETE,Command.TV))
        val direction=listOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK)
        val rails=listOf(left,right)
        if(direction.any { it in caps.commands })BoxWithConstraints(Modifier.fillMaxWidth()) {
            val beside=maxWidth>=304.dp
            val padSize=if(beside)minOf(maxWidth-112.dp,248.dp) else minOf(maxWidth,248.dp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
                if(beside)RockerSlot(vm,caps,left,padSize)
                Dpad(vm,caps,padSize)
                if(beside)RockerSlot(vm,caps,right,padSize)
            }
        }
        // On narrow screens, or hosts without D-pad commands, put volume/channel keys in a row.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if(maxWidth<304.dp || direction.none { it in caps.commands }) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { rails.forEach { CommandRow(vm,caps,it.keys) } }
            }
        }
        CommandRow(vm,caps,listOf(Command.BACK,Command.HOME,Command.MENU))
        CommandRow(vm,caps,listOf(Command.REWIND,Command.PLAY_PAUSE,Command.FORWARD))
        CommandRow(vm,caps,listOf(Command.MUTE,Command.GUIDE,Command.INFO))
        Text(strings.getString(R.string.ui_hold_arrows_volume_channels_or_seek_to_repeat_tv_and_input_behavi),fontSize=12.sp,color=Muted)
    }
    }
}
@Composable private fun RockerSlot(vm:RemoteViewModel,caps:Capabilities,kind:CanvasKind,height:androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(48.dp).height(height),verticalArrangement=Arrangement.Center) {
        if(kind.keys.all { it in caps.commands })Box(Modifier.fillMaxWidth().height(152.dp)) { RemoteRocker(vm,kind) }
        else kind.keys.filter { it in caps.commands }.forEach { RemoteCommand(vm,it,Modifier.fillMaxWidth().height(56.dp)) }
    }
}
@Composable private fun CommandRow(vm:RemoteViewModel,caps:Capabilities,commands:List<Command>,keepSlots:Boolean=false) {
    if(commands.none { it in caps.commands })return
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        commands.forEach { command ->
            if(command in caps.commands)RemoteCommand(vm,command,Modifier.weight(1f).height(56.dp))
            else if(keepSlots)Spacer(Modifier.weight(1f))
        }
    }
}
