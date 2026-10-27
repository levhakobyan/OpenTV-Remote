package com.luma.remote.ui.remote.controls

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*
import com.luma.remote.ui.*

/** The entire rail is one editor item; its two physical targets retain separate commands and holds. */
@Composable internal fun RemoteRocker(vm:RemoteViewModel,kind:CanvasKind) {
    val strings=LocalContext.current.resources

    val keys=kind.keys
    Surface(Modifier.fillMaxSize(),shape=RoundedCornerShape(24.dp),color=Panel) {
        Box {
            Column(Modifier.fillMaxSize()) {
                keys.forEachIndexed { n,command ->
                    RepeatingKey(vm,command,Modifier.fillMaxWidth().weight(1f),RoundedCornerShape(0.dp)) {
                        Icon(if(kind==CanvasKind.VOLUME)commandIcon(command) else if(n==0)Icons.Outlined.Add else Icons.Outlined.Remove,null,Modifier.size(26.dp),tint=if(kind==CanvasKind.VOLUME)Accent else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Surface(Modifier.align(Alignment.Center),color=Panel,shape=RoundedCornerShape(6.dp)) {
                Text(if(kind==CanvasKind.VOLUME)strings.getString(R.string.ui_vol) else strings.getString(R.string.ui_ch),Modifier.padding(horizontal=2.dp),fontSize=11.sp,lineHeight=14.sp,color=Muted,maxLines=1)
            }
        }
    }
}
@Composable internal fun RemoteButtonGroup(vm:RemoteViewModel,kind:CanvasKind) {
    Surface(Modifier.fillMaxSize(),shape=RoundedCornerShape(24.dp),color=Panel) {
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(1.dp)) {
            kind.keys.forEach { command -> RemoteCommand(vm,command,Modifier.weight(1f).fillMaxHeight(),joined=true) }
        }
    }
}
