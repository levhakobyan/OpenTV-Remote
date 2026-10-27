package com.luma.remote.ui.remote

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import com.luma.remote.ui.*
import com.luma.remote.ui.remote.controls.*
import com.luma.remote.ui.remote.editor.*
import androidx.compose.foundation.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*

private enum class RemotePanel { NUMBERS, MORE, SHORTCUTS, TV_SETUP, ALL_KEYS }
private val LocalRemoteLayout=staticCompositionLocalOf { RemoteLayout() }
private val controlTarget:Dp @Composable get()=LocalRemoteLayout.current.size.target.dp
private val controlHeight:Dp @Composable get()=controlTarget+8.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RemoteScreen(vm:RemoteViewModel,tv:TvDevice?,state:RemoteState,onInfo:()->Unit={},onAppearance:()->Unit={},onLanguage:()->Unit={}) {
    val strings=LocalContext.current.resources

    var panel by remember { mutableStateOf<RemotePanel?>(null) }
    var textEntry by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var canvasEditing by rememberSaveable { mutableStateOf(false) }
    var resetPairing by remember(tv?.id) { mutableStateOf(false) }
    var legacyConsent by remember(tv?.id) { mutableStateOf(false) }
    var motion by remember { mutableStateOf(false) }
    LaunchedEffect(panel,textEntry,menu,canvasEditing,motion) {
        if(panel!=null || textEntry || menu || canvasEditing || motion)vm.stopHolds()
    }
    var handledKeyboard by rememberSaveable(tv?.id) { mutableStateOf(0L) }
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val canvas by vm.canvas.collectAsStateWithLifecycle()
    val custom=canvas?.enabled==true
    var editorDeviceId by rememberSaveable { mutableStateOf(tv?.id) }
    val editCaps=rememberSaveable(tv?.id,canvasEditing,saver=listSaver<Capabilities,String>(
        save={listOf(it.text.toString(),it.pointer.toString(),it.apps.toString())+it.commands.map { command -> command.name }},
        restore={Capabilities(it.drop(3).mapNotNull { name -> Command.entries.firstOrNull { command -> command.name==name } }.toSet(),it[0].toBoolean(),it[1].toBoolean(),it[2].toBoolean())}
    )) { state.capabilities }
    val editState=state.copy(capabilities=editCaps)
    LaunchedEffect(tv?.id) { if(editorDeviceId!=tv?.id) { canvasEditing=false;editorDeviceId=tv?.id };motion=false }
    val caps=state.capabilities
    LaunchedEffect(tv?.id,state.phase) { if(state.phase!=Phase.READY) { panel=null; textEntry=false;menu=false;motion=false } }
    LaunchedEffect(state.keyboardRequest?.id,panel) {
        val request=state.keyboardRequest
        if(request!=null && request.id!=handledKeyboard && vm.autoKeyboard && state.phase==Phase.READY) {
            if(textEntry)handledKeyboard=request.id
            else if(panel==null) { handledKeyboard=request.id; textEntry=true }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=if(custom)6.dp else 20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(tv==null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(strings.getString(R.string.ui_connect_your_tv),fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                Text(strings.getString(R.string.ui_find_a_nearby_tv_or_choose_a_saved_one),color=Muted)
                Button(onClick={vm.page.value=1;vm.discovery.start()},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(16.dp)) { Icon(Icons.Outlined.Tv,null); Spacer(Modifier.width(12.dp)); Text(strings.getString(R.string.ui_choose_tv)) }
            }
        } else {
            if(state.phase==Phase.READY)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={canvasEditing=true}) { Icon(Icons.Outlined.Tune,strings.getString(R.string.ui_customize_remote),tint=Accent) }
                Text(if(custom && canvas!!.items.any { it.kind==CanvasKind.TOUCH } || !custom && layout.gestures)strings.getString(R.string.ui_touchpad) else strings.getString(R.string.ui_remote),Modifier.weight(1f),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                IconButton(onClick={vm.page.value=1}) { Icon(Icons.Outlined.SwapHoriz,strings.getString(R.string.ui_switch_tv),tint=Muted) }
                if(!custom && (caps.pointer || GestureNavigation.supported(caps)))IconButton(onClick={vm.saveLayout(layout.copy(gestures=!layout.gestures))},modifier=Modifier.semantics { stateDescription=if(layout.gestures)strings.getString(R.string.ui_touch) else strings.getString(R.string.ui_d_pad) }) { Icon(if(layout.gestures)Icons.Outlined.ControlCamera else Icons.Outlined.TouchApp,if(layout.gestures)strings.getString(R.string.ui_use_d_pad) else strings.getString(R.string.ui_use_touch_navigation),tint=if(layout.gestures)Accent else Muted) }
                Box {
                    IconButton(onClick={menu=true}) { Icon(Icons.Outlined.MoreVert,strings.getString(R.string.ui_remote_menu),tint=Muted) }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        if(caps.pointer || GestureNavigation.supported(caps))DropdownMenuItem(text={Text(strings.getString(R.string.ui_motion_remote))},leadingIcon={Icon(Icons.Outlined.ScreenRotation,null)},onClick={menu=false;motion=true})
                        if(caps.apps)DropdownMenuItem(text={Text(strings.getString(R.string.ui_app_shortcuts))},leadingIcon={Icon(Icons.Outlined.Apps,null)},onClick={menu=false;panel=RemotePanel.SHORTCUTS})
                        if(caps.text)DropdownMenuItem(text={Text(strings.getString(R.string.ui_keyboard))},leadingIcon={Icon(Icons.Outlined.Keyboard,null)},onClick={menu=false;textEntry=true})
                        if(Command.TV in caps.commands || Command.INPUT in caps.commands || Command.CHANNEL_UP in caps.commands || caps.apps)DropdownMenuItem(text={Text(strings.getString(R.string.ui_tv_button))},leadingIcon={Icon(Icons.Outlined.Tv,null)},onClick={menu=false;panel=RemotePanel.TV_SETUP})
                        DropdownMenuItem(text={Text(strings.getString(R.string.ui_all_controls))},leadingIcon={Icon(Icons.Outlined.SettingsRemote,null)},onClick={menu=false;panel=RemotePanel.ALL_KEYS})
                        DropdownMenuItem(text={Text(strings.getString(R.string.ui_appearance))},leadingIcon={Icon(Icons.Outlined.Palette,null)},onClick={menu=false;onAppearance()})
                        DropdownMenuItem(text={Text(strings.getString(R.string.ui_language))},leadingIcon={Icon(Icons.Outlined.Language,null)},onClick={menu=false;onLanguage()})
                        DropdownMenuItem(text={Text(strings.getString(R.string.ui_about_settings))},leadingIcon={Icon(Icons.Outlined.Info,null)},onClick={menu=false;onInfo()})
                    }
                }
                if(Command.POWER in caps.commands) {
                    val haptic=LocalHapticFeedback.current
                    FilledTonalIconButton(onClick={if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);vm.send(Command.POWER)},modifier=Modifier.size(48.dp)) { Icon(Icons.Outlined.PowerSettingsNew,strings.getString(R.string.ui_power)) }
                }
            } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(tv.name,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                IconButton(onClick={vm.page.value=1}) { Icon(Icons.Outlined.SwapHoriz,strings.getString(R.string.ui_switch_tv),tint=Muted) }
            }
            if(state.phase!=Phase.READY) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(state.phase==Phase.CONNECTING && state.identity==null)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        Text(strings.getString(state.messageRes),color=if(state.phase==Phase.ERROR)MaterialTheme.colorScheme.error else Muted,fontSize=14.sp)
                    }
                    state.identity?.let { identity ->
                        Text(strings.getString(R.string.ui_confirm_tv_identity),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                        Text(strings.getString(R.string.ui_samsung_tvs_use_local_certificates_continue_only_for_the_tv_you_s),color=Muted,fontSize=13.sp)
                        Text(strings.getString(R.string.ui_certificate_sha_256),color=Muted,fontSize=12.sp)
                        Text(identity.fingerprint.chunked(4).joinToString(":"),fontSize=12.sp,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)
                        Button(onClick=vm::trustTvIdentity,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_trust_tv_continue)) }
                        TextButton(onClick=vm::cancelConnectionSelection) { Text(strings.getString(R.string.ui_back_to_devices)) }
                    }
                    if(state.legacyConnection)TextButton(onClick=vm::useSecureSamsung) { Text(strings.getString(R.string.ui_try_secure_wi_fi)) }
                    if(state.canResetPairing)OutlinedButton(onClick={resetPairing=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_reset_samsung_pairing)) }
                    if(state.legacyAvailable)OutlinedButton(onClick={legacyConsent=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_try_legacy_wi_fi)) }
                    when(state.phase) {
                        Phase.PAIRING -> {
                            if(tv.platform==Platform.ANDROID_TV)Pairing(vm) else Text(strings.getString(R.string.ui_accept_the_request_on_your_tv))
                            TextButton(onClick={if(tv.platform==Platform.SAMSUNG)vm.cancelConnectionSelection() else vm.forget(tv)}) { Text(strings.getString(R.string.ui_cancel)) }
                        }
                        Phase.CONNECTING -> Unit
                        else -> Button(onClick=vm::reconnect,modifier=Modifier.heightIn(min=48.dp)) { Icon(Icons.Outlined.Refresh,null); Spacer(Modifier.width(8.dp)); Text(strings.getString(R.string.ui_reconnect)) }
                    }
                }
            } else {
                if(state.legacyConnection)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(strings.getString(R.string.ui_legacy_wi_fi_unencrypted),Modifier.weight(1f),color=Muted,fontSize=12.sp)
                    TextButton(onClick=vm::useSecureSamsung) { Text(strings.getString(R.string.ui_try_secure_wi_fi),fontSize=12.sp) }
                }
                if(!state.message.startsWith("Connected"))Text(strings.getString(state.messageRes),color=MaterialTheme.colorScheme.error,fontSize=13.sp)
                if(custom)RemoteCanvasBoard(vm,canvas!!,state,shortcuts,Modifier.weight(1f),keyboard={textEntry=true},numbers={panel=RemotePanel.NUMBERS},tvSetup={if(layout.tvConfigured && (layout.tvAction.command in caps.commands || layout.tvAction==TvAction.APP && caps.apps && shortcuts.any { it.url==layout.tvApp }))vm.tvAction() else panel=RemotePanel.TV_SETUP},edit={canvasEditing=true})
                else CompositionLocalProvider(LocalRemoteLayout provides layout) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val wide=maxWidth>maxHeight && maxWidth>=480.dp
                        val viewportWidth=maxWidth
                        val viewportHeight=maxHeight
                        val density=LocalDensity.current
                        val measured=remember(tv.id,viewportWidth,wide) { mutableStateMapOf<RemoteBlock,Int>() }
                        var utilityHeight by remember(tv.id,viewportWidth,wide) { mutableIntStateOf(0) }
                        val pad=if(maxHeight<550.dp)192.dp else 224.dp
                        val blocks=layout.order.filter { block -> block !in layout.hidden && when(block) {
                            RemoteBlock.DEVICE -> true
                            RemoteBlock.APPS -> caps.apps && shortcuts.isNotEmpty()
                            RemoteBlock.CONTROL -> caps.pointer || caps.commands.any { it in setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK,Command.VOLUME_UP,Command.CHANNEL_UP) }
                            RemoteBlock.NAVIGATION -> caps.commands.any { it in setOf(Command.BACK,Command.HOME) } || layout.tvAction.command in caps.commands || layout.tvAction==TvAction.APP && caps.apps
                            RemoteBlock.PLAYBACK -> Command.PLAY_PAUSE in caps.commands
                            RemoteBlock.NUMBERS -> AndroidTvProtocol.digits.all { it in caps.commands }
                        } }
                        val hasUtilities=caps.text || caps.apps || AndroidTvProtocol.digits.all { it in caps.commands } || caps.commands.any { it in extraKeys }
                        fun estimate(block:RemoteBlock):Dp {
                            val height=(layout.blockSizes[block] ?: layout.size).target.dp+8.dp
                            return when(block) { RemoteBlock.DEVICE -> 48.dp;RemoteBlock.NUMBERS -> height*4+52.dp;else -> height }
                        }
                        val otherHeight=blocks.filter { it!=RemoteBlock.CONTROL }.fold(0.dp) { sum,block -> sum+(measured[block]?.let { with(density) { it.toDp() } } ?: estimate(block)) }
                        val utilitySpace=if(hasUtilities) { if(utilityHeight>0)with(density) { utilityHeight.toDp() } else layout.size.target.dp+8.dp } else 0.dp
                        val gaps=8.dp*(blocks.size+(if(hasUtilities)1 else 0)-1).coerceAtLeast(0)
                        val controlRoom=if(layout.fitScreen)(viewportHeight-otherHeight-utilitySpace-gaps).coerceAtLeast(0.dp) else 0.dp
                        @Composable fun Utilities() {
                            Box(Modifier.fillMaxWidth().onSizeChanged { if(utilityHeight!=it.height)utilityHeight=it.height }) {
                                UtilityKeys(caps,{textEntry=true},{panel=RemotePanel.NUMBERS},{panel=RemotePanel.MORE},{panel=RemotePanel.SHORTCUTS})
                            }
                        }
                        @Composable fun Block(block:RemoteBlock) { key(block) {
                            val minHeight=if(block!=RemoteBlock.CONTROL || !layout.fitScreen)0.dp else if(wide)viewportHeight else controlRoom
                            Box(Modifier.fillMaxWidth().heightIn(min=minHeight).onSizeChanged { if(block!=RemoteBlock.CONTROL && measured[block]!=it.height)measured[block]=it.height },contentAlignment=Alignment.Center) {
                            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            CompositionLocalProvider(LocalRemoteLayout provides layout.copy(size=layout.blockSizes[block] ?: layout.size)) {
                            when(block) {
                                RemoteBlock.DEVICE -> TextButton(onClick={vm.page.value=1},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=12.dp)) {
                                    Icon(Icons.Outlined.CheckCircle,strings.getString(R.string.ui_connected),Modifier.size(16.dp),tint=Accent)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tv.name,Modifier.weight(1f),fontSize=13.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Icon(Icons.Outlined.SwapHoriz,strings.getString(R.string.ui_switch_tv),Modifier.size(20.dp),tint=Muted)
                                }
                                RemoteBlock.APPS -> QuickApps(vm,shortcuts)
                                RemoteBlock.CONTROL -> {
                                    if(layout.gestures && (caps.pointer || GestureNavigation.supported(caps))) {
                                        GesturePad(vm,tv.id,caps)
                                        AudioKeys(vm,state)
                                        if(hasChannels(caps))Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            RemoteKey(vm,Command.CHANNEL_DOWN,Icons.Outlined.Remove,Modifier.weight(1f),showLabel=true)
                                            RemoteKey(vm,Command.CHANNEL_UP,Icons.Outlined.Add,Modifier.weight(1f),showLabel=true)
                                        }
                                    } else ControlCluster(vm,state,pad+(LocalRemoteLayout.current.size.target-48).dp*2,if(layout.fitScreen) { if(wide)viewportHeight else controlRoom } else null)
                                }
                                RemoteBlock.NAVIGATION -> NavigationKeys(vm,caps,shortcuts,{panel=RemotePanel.TV_SETUP})
                                RemoteBlock.PLAYBACK -> PlaybackKeys(vm,caps)
                                RemoteBlock.NUMBERS -> NumberPad(vm,caps,inline=true)
                            }
                            }
                            }
                            }
                        } }
                        if(wide)Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            val target=(layout.blockSizes[RemoteBlock.CONTROL] ?: layout.size).target.dp
                            val leftWidth=minOf(target*4+112.dp,viewportWidth*0.68f)
                            Column(Modifier.width(leftWidth).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) { if(RemoteBlock.CONTROL in blocks)Block(RemoteBlock.CONTROL) }
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                blocks.filter { it!=RemoteBlock.CONTROL }.forEach { Block(it) }
                                Utilities()
                            }
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            blocks.forEach { Block(it) }
                            Utilities()
                        }
                    }
                }
            }
        }
    }
    val editorInitial=remember(tv?.id,canvasEditing) { canvas ?: if(canvasEditing)RemoteCanvas.preset(layout.preset,editState.capabilities,shortcuts) else RemoteCanvas(emptyList()) }
    if(canvasEditing && tv!=null)RemoteCanvasEditor(vm,tv.id,editorInitial,editState,shortcuts) { canvasEditing=false }
    if(resetPairing)AlertDialog(containerColor=Panel,onDismissRequest={resetPairing=false},title={Text(strings.getString(R.string.ui_reset_samsung_pairing_120))},text={Text(strings.getString(R.string.ui_the_tv_certificate_changed_continue_only_if_you_expect_a_tv_reset))},confirmButton={TextButton(onClick={resetPairing=false;vm.resetSamsungPairing()}) { Text(strings.getString(R.string.ui_reset_pairing)) }},dismissButton={TextButton(onClick={resetPairing=false}) { Text(strings.getString(R.string.ui_cancel)) }})
    if(legacyConsent)AlertDialog(containerColor=Panel,onDismissRequest={legacyConsent=false},title={Text(strings.getString(R.string.ui_use_legacy_wi_fi))},text={Text(strings.getString(R.string.ui_this_tv_s_older_local_mode_sends_controls_and_typed_text_unencryp))},confirmButton={TextButton(onClick={legacyConsent=false;vm.useLegacySamsung()}) { Text(strings.getString(R.string.ui_use_legacy_wi_fi_125)) }},dismissButton={TextButton(onClick={legacyConsent=false}) { Text(strings.getString(R.string.ui_cancel)) }})
    if(motion)MotionRemoteDialog(vm,state) { motion=false }
    if(textEntry)TvKeyboardDialog(vm) { textEntry=false }
    panel?.let { active -> ModalBottomSheet(onDismissRequest={panel=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            when(active) {
                RemotePanel.NUMBERS -> CompositionLocalProvider(LocalRemoteLayout provides layout) { NumberPad(vm,caps) }
                RemotePanel.MORE -> CompositionLocalProvider(LocalRemoteLayout provides layout) { MoreControls(vm,caps) }
                RemotePanel.SHORTCUTS -> AppShortcuts(vm,shortcuts)
                RemotePanel.TV_SETUP -> TvButtonEditor(vm,layout,caps,shortcuts)
                RemotePanel.ALL_KEYS -> AllControlsRemote(vm,caps)
            }
        }
    } }
}

private fun hasVolume(caps:Capabilities)=Command.VOLUME_UP in caps.commands && Command.VOLUME_DOWN in caps.commands
private fun hasChannels(caps:Capabilities)=Command.CHANNEL_UP in caps.commands && Command.CHANNEL_DOWN in caps.commands

@Composable private fun ControlCluster(vm:RemoteViewModel,state:RemoteState,preferredSize:Dp,availableHeight:Dp?=null) {
    val strings=LocalContext.current.resources

    val caps=state.capabilities
    val volume=hasVolume(caps)
    val channels=hasChannels(caps)
    val navigation=caps.commands.any { it in setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.OK) }
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
        val availableWidth=maxWidth
        val target=controlTarget
        val minimumPad=maxOf(192.dp,target*2+96.dp)
        val railSpace=(target+8.dp)*((if(volume)1 else 0)+(if(channels)1 else 0))
        val fallback= !navigation || availableWidth-railSpace<minimumPad
        val keyHeight=target+8.dp
        val channelHeight=maxOf(keyHeight,(41+18*LocalDensity.current.fontScale).dp)
        val extras=if(fallback) (if(volume)keyHeight+8.dp else 0.dp)+(if(channels)channelHeight+8.dp else 0.dp) else 0.dp
        val preferred=if(availableHeight!=null)maxOf(preferredSize,minOf(availableWidth,(availableHeight-extras).coerceAtLeast(0.dp))) else preferredSize
        if(fallback) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(navigation)Dpad(vm,caps,minOf(preferred,availableWidth),controlTarget)
                AudioKeys(vm,state)
                if(channels)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    RemoteKey(vm,Command.CHANNEL_DOWN,Icons.Outlined.Remove,Modifier.weight(1f),showLabel=true)
                    RemoteKey(vm,Command.CHANNEL_UP,Icons.Outlined.Add,Modifier.weight(1f),showLabel=true)
                }
            }
        } else {
            val pad=minOf(maxOf(preferred,minimumPad),availableWidth-railSpace)
            val labelHeight=maxOf(24.dp,(16*androidx.compose.ui.platform.LocalDensity.current.fontScale).dp)
            val rockerHeight=target*2+8.dp+labelHeight
            val mute=volume && Command.MUTE in caps.commands
            val rockerTop=((pad-rockerHeight)/2).coerceAtLeast(0.dp)
            val channelTop=rockerTop
            val muteTop=rockerTop+rockerHeight+8.dp
            val height=if(mute)maxOf(pad,muteTop+target) else pad
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top) {
                val volumeRight=LocalRemoteLayout.current.volumeRight
                @Composable fun Rail(isVolume:Boolean) {
                    if(isVolume && volume)Box(Modifier.width(target).height(height)) {
                        SideRocker(vm,strings.getString(R.string.ui_vol),Command.VOLUME_UP,Command.VOLUME_DOWN,labelHeight,Modifier.offset(y=rockerTop))
                        if(mute)RailKey(vm,Command.MUTE,Icons.Outlined.VolumeOff,Modifier.offset(y=muteTop))
                    }
                    if(!isVolume && channels)Box(Modifier.width(target).height(height)) {
                        SideRocker(vm,strings.getString(R.string.ui_ch),Command.CHANNEL_UP,Command.CHANNEL_DOWN,labelHeight,Modifier.offset(y=channelTop))
                    }
                }
                Rail(volumeRight.not())
                Dpad(vm,caps,pad,controlTarget)
                Rail(volumeRight)
            }
        }
    }
}
@Composable private fun SideRocker(vm:RemoteViewModel,label:String,up:Command,down:Command,labelHeight:Dp,modifier:Modifier=Modifier) {
    Surface(modifier.width(controlTarget),shape=RoundedCornerShape(24.dp),color=Panel) {
        Column(Modifier.padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            RailKey(vm,up,Icons.Outlined.Add)
            Box(Modifier.fillMaxWidth().height(labelHeight),contentAlignment=Alignment.Center) { Text(label,fontSize=12.sp,lineHeight=16.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis) }
            RailKey(vm,down,Icons.Outlined.Remove)
        }
    }
}
@Composable private fun RailKey(vm:RemoteViewModel,command:Command,icon:ImageVector,modifier:Modifier=Modifier) {
    val strings=LocalContext.current.resources

    if(command in repeatingCommands) {
        RepeatingKey(vm,command,modifier.size(controlTarget)) { Icon(icon,null,Modifier.size(24.dp)) }
        return
    }
    val haptic=LocalHapticFeedback.current
    FilledTonalIconButton(onClick={if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);vm.send(command)},modifier=modifier.size(controlTarget),colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Panel,contentColor=MaterialTheme.colorScheme.onSurface)) { Icon(icon,strings.label(command),Modifier.size(24.dp)) }
}

@Composable private fun NavigationKeys(vm:RemoteViewModel,caps:Capabilities,shortcuts:List<AppShortcut>,setupTv:()->Unit) {
    val strings=LocalContext.current.resources

    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(Command.BACK in caps.commands)RemoteKey(vm,Command.BACK,Icons.Outlined.KeyboardReturn,Modifier.weight(1f),showLabel=true)
        if(Command.HOME in caps.commands)RemoteKey(vm,Command.HOME,Icons.Outlined.Home,Modifier.weight(1f),showLabel=true)
        val action=LocalRemoteLayout.current
        if(!action.tvConfigured && (Command.TV in caps.commands || Command.INPUT in caps.commands))ActionTile(strings.getString(R.string.ui_tv_setup),Icons.Outlined.Tv,Modifier.weight(1f),showLabel=true,onClick=setupTv)
        else if(action.tvAction.command in caps.commands || action.tvAction==TvAction.APP && caps.apps && shortcuts.any { it.url==action.tvApp }) {
            val title=if(action.tvAction==TvAction.APP)shortcuts.firstOrNull { it.url==action.tvApp }?.name ?: strings.getString(R.string.ui_tv) else strings.label(action.tvAction)
            ActionTile(title,Icons.Outlined.Tv,Modifier.weight(1f),showLabel=true,onClick=vm::tvAction)
        }
    }
}
@Composable private fun AudioKeys(vm:RemoteViewModel,state:RemoteState) {
    val caps=state.capabilities
    if(Command.VOLUME_UP in caps.commands)Surface(color=Panel,shape=RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            RemoteKey(vm,Command.VOLUME_DOWN,Icons.Outlined.Remove,Modifier.weight(1f))
            Column(Modifier.widthIn(min=40.dp),horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Outlined.VolumeUp,null,Modifier.size(20.dp),tint=Muted); state.volume?.let { Text("$it",fontSize=11.sp,color=Muted) } }
            RemoteKey(vm,Command.VOLUME_UP,Icons.Outlined.Add,Modifier.weight(1f))
            if(Command.MUTE in caps.commands)RemoteKey(vm,Command.MUTE,Icons.Outlined.VolumeOff,Modifier.weight(1f))
        }
    }
}
@Composable private fun PlaybackKeys(vm:RemoteViewModel,caps:Capabilities) {
    if(Command.PLAY_PAUSE in caps.commands)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(Command.REWIND in caps.commands)RemoteKey(vm,Command.REWIND,Icons.Outlined.FastRewind,Modifier.weight(1f))
        RemoteKey(vm,Command.PLAY_PAUSE,Icons.Outlined.PlayArrow,Modifier.weight(1.2f),accent=true)
        if(Command.FORWARD in caps.commands)RemoteKey(vm,Command.FORWARD,Icons.Outlined.FastForward,Modifier.weight(1f))
    }
}
@Composable private fun UtilityKeys(caps:Capabilities,keyboard:()->Unit,numbers:()->Unit,more:()->Unit,apps:()->Unit) {
    val strings=LocalContext.current.resources

    val items=buildList<Triple<String,ImageVector,()->Unit>> {
        if(caps.text)add(Triple(strings.getString(R.string.ui_keyboard),Icons.Outlined.Keyboard,keyboard))
        if(AndroidTvProtocol.digits.all { it in caps.commands })add(Triple(strings.getString(R.string.ui_numbers),Icons.Outlined.Dialpad,numbers))
        if(caps.apps)add(Triple(strings.getString(R.string.ui_app_shortcuts),Icons.Outlined.Apps,apps))
        if(caps.commands.any { it in extraKeys })add(Triple(strings.getString(R.string.ui_more_controls),Icons.Outlined.MoreHoriz,more))
    }
    if(items.isEmpty())return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns=if(maxWidth>=48.dp*items.size+8.dp*(items.size-1))items.size else if(maxWidth>=104.dp)minOf(2,items.size) else 1
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { (label,icon,action) -> ActionTile(label,icon,Modifier.weight(1f),onClick=action) }
                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
}
private val extraKeys=listOf(Command.INPUT,Command.CHANNEL_UP,Command.CHANNEL_DOWN,Command.GUIDE,Command.APPS,Command.MENU,Command.INFO,Command.SETTINGS)
@Composable private fun MoreControls(vm:RemoteViewModel,caps:Capabilities) {
    val strings=LocalContext.current.resources

    Text(strings.getString(R.string.ui_tv_controls),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
    val available=extraKeys.filter { it in caps.commands }
    available.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        row.forEach { command -> RemoteKey(vm,command,commandIcon(command),Modifier.weight(1f),showLabel=true) }
        repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
    } }
    Text(strings.getString(R.string.ui_tv_input_and_channel_keys_depend_on_your_tv_and_current_app),color=Muted,fontSize=12.sp)
}
@Composable private fun NumberPad(vm:RemoteViewModel,caps:Capabilities,inline:Boolean=false) {
    val strings=LocalContext.current.resources

    Text(strings.getString(R.string.ui_numbers),fontSize=if(inline)14.sp else 22.sp,fontWeight=FontWeight.SemiBold)
    if(!inline && Command.CHANNEL_UP in caps.commands)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        RemoteKey(vm,Command.CHANNEL_DOWN,Icons.Outlined.Remove,Modifier.weight(1f),showLabel=true)
        RemoteKey(vm,Command.CHANNEL_UP,Icons.Outlined.Add,Modifier.weight(1f),showLabel=true)
    }
    AndroidTvProtocol.digits.drop(1).chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { row.forEach { NumberKey(vm,it,Modifier.weight(1f)) } } }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(Command.DELETE in caps.commands)RemoteKey(vm,Command.DELETE,Icons.Outlined.Backspace,Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        NumberKey(vm,Command.ZERO,Modifier.weight(1f))
        if(Command.OK in caps.commands)RemoteKey(vm,Command.OK,Icons.Outlined.Check,Modifier.weight(1f),accent=true) else Spacer(Modifier.weight(1f))
    }
    if(!inline)Text(strings.getString(R.string.ui_number_keys_are_sent_immediately_to_the_tv),color=Muted,fontSize=12.sp)
}
@Composable private fun NumberKey(vm:RemoteViewModel,command:Command,modifier:Modifier) {
    val strings=LocalContext.current.resources

    val haptic=LocalHapticFeedback.current
    FilledTonalButton(onClick={if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);vm.send(command)},modifier=modifier.heightIn(min=controlHeight),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=Raised)) { Text(strings.label(command),fontSize=24.sp) }
}
@Composable private fun QuickApps(vm:RemoteViewModel,shortcuts:List<AppShortcut>) {
    if(shortcuts.isEmpty())return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked=maxWidth<300.dp
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            shortcuts.take(2).forEach { shortcut -> FilledTonalButton(onClick={vm.launchApp(shortcut)},modifier=Modifier.weight(1f).heightIn(min=controlHeight).semantics { contentDescription=shortcut.name },contentPadding=PaddingValues(8.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=Panel)) {
                if(stacked)Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) { ShortcutBadge(shortcut,Modifier.size(22.dp));Text(shortcut.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp) }
                else { ShortcutBadge(shortcut,Modifier.size(22.dp));Spacer(Modifier.width(8.dp));Text(shortcut.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp) }
            } }
        }
    }
}
@Composable private fun AppShortcuts(vm:RemoteViewModel,shortcuts:List<AppShortcut>) {
    val strings=LocalContext.current.resources

    var editor by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(false) }
    Text(strings.getString(R.string.ui_app_shortcuts),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
    Text(strings.getString(R.string.ui_saved_apps_are_available_in_add_controls_apps_opening_depends_on),fontSize=13.sp,color=Muted)
    shortcuts.forEachIndexed { index,shortcut -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick={vm.launchApp(shortcut)},modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=Raised)) {
            ShortcutBadge(shortcut,Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)); Text(shortcut.name)
        }
        IconButton(onClick={ if(index>0) { val updated=shortcuts.toMutableList();updated.removeAt(index);updated.add(index-1,shortcut);vm.saveShortcuts(updated) } },enabled=index>0) { Icon(Icons.Outlined.KeyboardArrowUp,strings.getString(R.string.ui_move_1_s_up, shortcut.name)) }
        IconButton(onClick={vm.saveShortcuts(shortcuts-shortcut)}) { Icon(Icons.Outlined.Close,strings.getString(R.string.ui_remove_1_s, shortcut.name)) }
    } }
    if(shortcuts.size<8)OutlinedButton(onClick={catalog=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(Icons.Outlined.Add,null); Spacer(Modifier.width(8.dp)); Text(strings.getString(R.string.ui_choose_apps)) }
    if(shortcuts.size<8)TextButton(onClick={editor=true}) { Text(strings.getString(R.string.ui_add_custom_app)) }
    if(catalog)ShortcutCatalogDialog(vm,shortcuts) { catalog=false }
    TextButton(onClick={vm.saveShortcuts(AppShortcut.defaults)}) { Text(strings.getString(R.string.ui_restore_youtube_netflix)) }
    if(editor)ShortcutEditor(shortcuts,onDismiss={editor=false}) { candidate -> vm.saveShortcuts(shortcuts+candidate);editor=false }
}
@Composable private fun ShortcutCatalogDialog(vm:RemoteViewModel,shortcuts:List<AppShortcut>,dismiss:()->Unit) {
    val strings=LocalContext.current.resources

    AlertDialog(onDismissRequest=dismiss,containerColor=Panel,title={Text(strings.getString(R.string.ui_choose_apps))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(strings.getString(R.string.ui_ready_made_links_install_the_app_on_your_tv_first_opening_depends),fontSize=13.sp,color=Muted)
            ShortcutCatalog.apps.forEach { app ->
                val added=shortcuts.any { it.url==app.url }
                OutlinedButton(onClick={vm.saveShortcuts(shortcuts+app)},enabled=!added && shortcuts.size<8,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(16.dp)) {
                    ShortcutBadge(app,Modifier.size(24.dp));Spacer(Modifier.width(12.dp))
                    Text(app.name,Modifier.weight(1f))
                    Icon(if(added)Icons.Outlined.Check else Icons.Outlined.Add,if(added)strings.getString(R.string.ui_1_s_added, app.name) else strings.getString(R.string.ui_add_1_s, app.name))
                }
            }
        }
    },confirmButton={TextButton(onClick=dismiss) { Text(strings.getString(R.string.ui_done)) }})
}

@Composable private fun RemoteKey(vm:RemoteViewModel,command:Command,icon:ImageVector,modifier:Modifier=Modifier,showLabel:Boolean=false,accent:Boolean=false) {
    val strings=LocalContext.current.resources

    if(command in pressCommands) {
        RepeatingKey(vm,command,modifier.heightIn(min=controlHeight),RoundedCornerShape(16.dp),prominent=accent || command==Command.OK) {
            Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Icon(icon,null,Modifier.size(23.dp))
                if(showLabel) {
                    Text(if(command==Command.CHANNEL_UP || command==Command.CHANNEL_DOWN)strings.getString(R.string.ui_ch) else strings.label(command),fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    val haptic=LocalHapticFeedback.current
    ActionTile(strings.label(command),icon,modifier,showLabel,accent) { if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);vm.send(command) }
}
@Composable private fun ActionTile(label:String,icon:ImageVector,modifier:Modifier=Modifier,showLabel:Boolean=false,accent:Boolean=false,onClick:()->Unit) {
    FilledTonalButton(onClick=onClick,modifier=modifier.heightIn(min=controlHeight).then(if(showLabel)Modifier else Modifier.semantics { contentDescription=label }),shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(accent)Accent else Panel,contentColor=if(accent)Ink else MaterialTheme.colorScheme.onSurface)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) { Icon(icon,null,Modifier.size(23.dp)); if(showLabel)Text(label,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis) }
    }
}
