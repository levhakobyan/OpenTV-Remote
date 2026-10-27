package com.luma.remote.ui.remote.editor

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.protocol.*
import com.luma.remote.ui.*
import com.luma.remote.ui.remote.controls.commandIcon

/** Read-only drawings: choosing a card only adds a draft item, never contacts a TV. */
@Composable internal fun RemoteControlPicker(caps:Capabilities,apps:List<AppShortcut>,tab:Int,onTab:(Int)->Unit,notice:String?,customApp:()->Unit,add:(CanvasKind,Command?,String?)->Unit) {
    val strings=LocalContext.current.resources

    var search by rememberSaveable { mutableStateOf("") }
    val order=listOf(CanvasKind.VOLUME,CanvasKind.CHANNELS,CanvasKind.NAVIGATION,CanvasKind.PLAYBACK,CanvasKind.DPAD,CanvasKind.TOUCH,CanvasKind.NUMBERS,CanvasKind.KEYBOARD,CanvasKind.TV)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(strings.getString(R.string.ui_add_control),fontSize=22.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(strings.getString(R.string.ui_widgets) to Icons.Outlined.Widgets,strings.getString(R.string.ui_buttons) to Icons.Outlined.RadioButtonChecked,strings.getString(R.string.ui_apps) to Icons.Outlined.Apps).forEachIndexed { index,(label,icon) ->
                FilterChip(selected=tab==index,onClick={onTab(index)},label={Text(label,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)},leadingIcon=if(LocalDensity.current.fontScale<1.4f)({Icon(icon,null,Modifier.size(18.dp))}) else null,modifier=Modifier.weight(1f).heightIn(min=48.dp))
            }
        }
        OutlinedTextField(search,{search=it},label={Text(strings.getString(R.string.ui_find_a_control))},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(search.isNotEmpty())IconButton(onClick={search=""}) { Icon(Icons.Outlined.Close,strings.getString(R.string.ui_clear_search)) }},modifier=Modifier.fillMaxWidth())
        when(tab) {
            0 -> {
                val kinds=order.filter { strings.label(it).contains(search,true) && CanvasItem(kind=it,x=0,y=0).supported(caps,apps) }
                if(kinds.isEmpty())Text(strings.getString(R.string.ui_no_matching_controls),color=Muted)
                kinds.chunked(2).forEach { row -> Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { kind -> PickerCard(strings.label(kind),Modifier.weight(1f).fillMaxHeight(),tall=true,onClick={add(kind,null,null)}) { WidgetDrawing(kind) } }
                    if(row.size==1)Spacer(Modifier.weight(1f))
                } }
            }
            1 -> {
                val commands=caps.commands.filter { strings.label(it).contains(search,true) }
                if(commands.isEmpty())Text(strings.getString(R.string.ui_no_matching_buttons),color=Muted)
                commands.chunked(3).forEach { row -> Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { command -> PickerCard(strings.label(command),Modifier.weight(1f).fillMaxHeight(),onClick={add(CanvasKind.KEY,command,null)},caption=command !in AndroidTvProtocol.digits) {
                        if(command in AndroidTvProtocol.digits)Text(strings.label(command),fontSize=26.sp,color=Accent)
                        else Icon(commandIcon(command),null,Modifier.size(30.dp),tint=Accent)
                    } }
                    repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
                } }
            }
            2 -> {
                if(caps.apps)OutlinedButton(onClick=customApp,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(18.dp)) {
                    Icon(Icons.Outlined.AddLink,null);Spacer(Modifier.width(10.dp));Text(strings.getString(R.string.ui_custom_app_or_link))
                }
                val available=if(caps.apps)apps.filter { it.name.contains(search,true) } else emptyList()
                if(!caps.apps)Text(strings.getString(R.string.ui_this_tv_does_not_expose_app_launching),color=Muted)
                else if(available.isEmpty())Text(strings.getString(R.string.ui_no_matching_apps),color=Muted)
                available.chunked(2).forEach { row -> Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { app -> PickerCard(app.name,Modifier.weight(1f).fillMaxHeight(),onClick={add(CanvasKind.APP,null,app.url)}) { ShortcutBadge(app,Modifier.size(40.dp)) } }
                    if(row.size==1)Spacer(Modifier.weight(1f))
                } }
            }
        }
        notice?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
    }
}
@Composable private fun PickerCard(label:String,modifier:Modifier,tall:Boolean=false,caption:Boolean=true,onClick:()->Unit,preview:@Composable ()->Unit) {
    val strings=LocalContext.current.resources

    Surface(onClick=onClick,modifier=modifier.heightIn(min=if(tall)144.dp else 108.dp).semantics { contentDescription=strings.getString(R.string.ui_add_1_s, label) },color=Raised,shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(10.dp).clearAndSetSemantics {},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically)) {
            Box(Modifier.fillMaxWidth().height(if(tall)88.dp else 48.dp),contentAlignment=Alignment.Center) { preview() }
            if(caption)Text(label,fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun MiniText(value:String) { Text(value,fontSize=(10/LocalDensity.current.fontScale).sp,lineHeight=(12/LocalDensity.current.fontScale).sp,color=Muted) }
@Composable private fun MiniIcon(icon:ImageVector,lime:Boolean=false) { Icon(icon,null,Modifier.size(19.dp),tint=if(lime)Accent else MaterialTheme.colorScheme.onSurface) }
@Composable private fun WidgetDrawing(kind:CanvasKind) {
    val strings=LocalContext.current.resources

    when(kind) {
        CanvasKind.VOLUME,CanvasKind.CHANNELS -> Surface(Modifier.width(40.dp).height(84.dp),shape=RoundedCornerShape(18.dp),color=Panel) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceEvenly) { MiniIcon(if(kind==CanvasKind.VOLUME)Icons.Outlined.VolumeUp else Icons.Outlined.Add,kind==CanvasKind.VOLUME);MiniText(if(kind==CanvasKind.VOLUME)strings.getString(R.string.ui_vol) else strings.getString(R.string.ui_ch));MiniIcon(if(kind==CanvasKind.VOLUME)Icons.Outlined.VolumeDown else Icons.Outlined.Remove,kind==CanvasKind.VOLUME) }
        }
        CanvasKind.NAVIGATION,CanvasKind.PLAYBACK -> Surface(Modifier.widthIn(max=132.dp).fillMaxWidth().height(48.dp),shape=RoundedCornerShape(16.dp),color=Panel) {
            Row(horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) { kind.keys.forEach { key ->
                Box(Modifier.size(34.dp).background(if(key==Command.PLAY_PAUSE)Accent else Panel,RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) { Icon(commandIcon(key),null,Modifier.size(22.dp),tint=if(key==Command.PLAY_PAUSE)Ink else MaterialTheme.colorScheme.onSurface) }
            } }
        }
        CanvasKind.DPAD -> Box(Modifier.size(84.dp).background(Panel,CircleShape),contentAlignment=Alignment.Center) {
            Icon(Icons.Outlined.KeyboardArrowUp,null,Modifier.align(Alignment.TopCenter).size(20.dp),tint=Muted)
            Icon(Icons.Outlined.KeyboardArrowDown,null,Modifier.align(Alignment.BottomCenter).size(20.dp),tint=Muted)
            Icon(Icons.Outlined.KeyboardArrowLeft,null,Modifier.align(Alignment.CenterStart).size(20.dp),tint=Muted)
            Icon(Icons.Outlined.KeyboardArrowRight,null,Modifier.align(Alignment.CenterEnd).size(20.dp),tint=Muted)
            Box(Modifier.size(30.dp).background(Accent,CircleShape),contentAlignment=Alignment.Center) { Icon(Icons.Outlined.Check,null,Modifier.size(18.dp),tint=Ink) }
        }
        CanvasKind.TOUCH -> Box(Modifier.width(100.dp).height(84.dp).background(Panel,RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center) {
            Icon(Icons.Outlined.Swipe,null,Modifier.size(32.dp),tint=Accent)
            Icon(Icons.Outlined.KeyboardArrowUp,null,Modifier.align(Alignment.TopCenter).size(18.dp),tint=Muted)
            Icon(Icons.Outlined.KeyboardArrowDown,null,Modifier.align(Alignment.BottomCenter).size(18.dp),tint=Muted)
        }
        CanvasKind.NUMBERS -> Surface(Modifier.width(70.dp).height(84.dp),shape=RoundedCornerShape(14.dp),color=Panel) {
            Column(Modifier.padding(7.dp),verticalArrangement=Arrangement.SpaceEvenly) {
                listOf("123","456","789"," 0 ").forEach { digits -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) { digits.forEach { MiniText(it.toString()) } } }
            }
        }
        CanvasKind.KEYBOARD -> Icon(Icons.Outlined.Keyboard,null,Modifier.size(48.dp),tint=Accent)
        CanvasKind.TV -> Icon(Icons.Outlined.Tv,null,Modifier.size(44.dp),tint=Accent)
        else -> Unit
    }
}
