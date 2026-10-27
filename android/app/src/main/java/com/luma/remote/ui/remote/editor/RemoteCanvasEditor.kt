package com.luma.remote.ui.remote.editor

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import com.luma.remote.ui.*
import com.luma.remote.ui.remote.ShortcutEditor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RemoteCanvasEditor(vm:RemoteViewModel,deviceId:String,initial:RemoteCanvas,state:RemoteState,apps:List<AppShortcut>,dismiss:()->Unit) {
    val strings=LocalContext.current.resources

    var raw by rememberSaveable(deviceId) { mutableStateOf(initial.compacted().encode()) }
    val draft=(RemoteCanvas.decode(raw) ?: initial).compacted()
    val undo=remember { mutableStateListOf<String>() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var palette by rememberSaveable { mutableStateOf(initial.items.isEmpty()) }
    var pickerTab by rememberSaveable { mutableIntStateOf(0) }
    var resizeMode by rememberSaveable(selected) { mutableStateOf(false) }
    var sizes by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingTargets by rememberSaveable(deviceId) { mutableStateOf(arrayListOf<String>()) }
    val pendingApps=pendingTargets.flatMap { ShortcutCodec.decode(it) }.distinctBy { it.url }
    var customApp by rememberSaveable { mutableStateOf(false) }
    val hand by vm.hand.collectAsStateWithLifecycle()
    val savedPresets by vm.presets.collectAsStateWithLifecycle()
    val allApps=(apps+pendingApps+ShortcutCatalog.apps).distinctBy { it.url }
    fun update(value:RemoteCanvas) {
        if(!value.valid())return
        val compact=value.compacted()
        if(compact!=draft) { undo.add(raw);if(undo.size>20)undo.removeAt(0);raw=compact.encode();notice=null }
    }
    fun change(value:RemoteCanvas?) { if(value==null)notice=strings.getString(R.string.ui_not_enough_room_below_or_the_size_is_outside_the_grid) else update(value) }
    fun add(kind:CanvasKind,command:Command?=null,app:String?=null):Boolean {
        val next=draft.add(kind,command,app)
        val appCount=(apps.map { it.url }+next?.items.orEmpty().filter { it.kind==CanvasKind.APP }.mapNotNull { it.app }).distinct().size
        if(next==null)notice=strings.getString(R.string.ui_layout_limit_reached_remove_a_control_or_free_space_to_add_anothe)
        else if(appCount>8)notice=strings.getString(R.string.ui_at_most_8_saved_apps_remove_a_saved_app_from_app_shortcuts_first)
        else { update(next);selected=next.items.last().id;palette=false;return true }
        return false
    }
    fun applyPreset(value:RemoteCanvas,targets:List<AppShortcut>) {
        val needed=value.items.filter { it.kind==CanvasKind.APP }.mapNotNull { item -> targets.firstOrNull { it.url==item.app } }.filter { target -> apps.none { it.url==target.url } }.distinctBy { it.url }
        if(apps.size+needed.size>8) { notice=strings.getString(R.string.ui_this_preset_needs_more_app_slots_remove_a_saved_app_from_app_shor);presets=false;return }
        pendingTargets=ArrayList((pendingApps+needed).distinctBy { it.url }.map { ShortcutCodec.encode(listOf(it)) });update(value);selected=null;presets=false
    }
    val item=draft.items.firstOrNull { it.id==selected }
    val joinCandidate=remember(raw,selected) { selected?.let { id -> draft.joinMatchingButtons(id).takeIf { it!=draft } } }
    fun joinSelected() {
        val joined=joinCandidate ?: return
        val group=joined.items.firstOrNull { it.kind.keys.isNotEmpty() && draft.items.firstOrNull { old -> old.id==it.id }!=it }
        update(joined);selected=group?.id
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=Ink) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=dismiss) { Icon(Icons.Outlined.Close,strings.getString(R.string.ui_cancel_editing)) }
                    Text(strings.getString(R.string.ui_edit_remote),Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    IconButton(onClick={if(undo.isNotEmpty()) { val restored=undo.removeAt(undo.lastIndex)
                        val previous=RemoteCanvas.decode(restored)
                        selected=previous?.items?.firstOrNull { old -> draft.items.none { it.id==old.id } }?.id
                            ?: previous?.items?.firstOrNull { old -> draft.items.firstOrNull { it.id==old.id }!=old }?.id
                        raw=restored }},enabled=undo.isNotEmpty()) { Icon(Icons.Outlined.Undo,strings.getString(R.string.ui_undo_layout_change)) }
                    TextButton(onClick={
                        val needed=draft.items.filter { it.kind==CanvasKind.APP }.mapNotNull { i -> allApps.firstOrNull { it.url==i.app } }.filter { a -> apps.none { it.url==a.url } }.distinctBy { it.url }
                        if(apps.size+needed.size>8)notice=strings.getString(R.string.ui_manage_saved_apps_first_at_most_8_app_shortcuts)
                        else { if(needed.isNotEmpty())vm.saveShortcuts(apps+needed);vm.saveCanvas(draft.copy(enabled=true));dismiss() }
                    },modifier=Modifier.heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_done)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(selected==null)strings.getString(R.string.ui_drag_to_move_tap_for_options) else if(resizeMode)strings.getString(R.string.ui_resize_controls_below_move_down) else strings.getString(R.string.ui_drag_or_tap_an_empty_space_to_move),Modifier.weight(1f),fontSize=12.sp,color=Muted)
                    TextButton(onClick={presets=true}) { Text(strings.getString(R.string.ui_presets)) }
                }
                RemoteCanvasBoard(vm,draft,state,allApps,Modifier.weight(1f).padding(horizontal=6.dp),editing=true,resizeMode=resizeMode,selected=selected,onSelect={selected=it;notice=null},onChange={update(it)},onInvalid={notice=strings.getString(R.string.ui_no_room_for_that_change_keep_controls_inside_the_grid)})
                notice?.let { Text(it,Modifier.padding(horizontal=12.dp,vertical=4.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.error) }
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                if(item!=null)Surface(color=Raised) {
                    Column(Modifier.fillMaxWidth().padding(horizontal=6.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(canvasLabel(item,allApps,strings),Modifier.weight(1f).padding(start=8.dp),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            FilledTonalButton(onClick={resizeMode=!resizeMode},contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription=if(resizeMode)strings.getString(R.string.ui_finish_resizing) else strings.getString(R.string.ui_resize_selected_control) },colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(resizeMode)Accent else Raised,contentColor=if(resizeMode)Ink else Accent)) { Icon(Icons.Outlined.OpenInFull,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text(if(resizeMode)strings.getString(R.string.ui_move) else strings.getString(R.string.ui_resize),fontSize=12.sp) }
                            TextButton(onClick={sizes=true},modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription=strings.getString(R.string.ui_size_1_s, canvasLabel(item,allApps,strings)) }) { Text("${item.w} × ${item.h}",fontSize=12.sp);Icon(Icons.Outlined.Tune,null,Modifier.size(18.dp)) }
                            if(item.kind.keys.isNotEmpty() || joinCandidate!=null)TextButton(onClick={if(joinCandidate!=null)joinSelected() else draft.split(item.id)?.let(::update)},contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription=if(joinCandidate!=null)strings.getString(R.string.ui_join_adjacent_buttons) else strings.getString(R.string.ui_split_1_s, canvasLabel(item,allApps,strings)) }) {
                                Icon(if(joinCandidate!=null)Icons.Outlined.Link else Icons.Outlined.LinkOff,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(if(joinCandidate!=null)strings.getString(R.string.ui_join) else strings.getString(R.string.ui_split),fontSize=12.sp)
                            }
                            IconButton(onClick={update(draft.copy(items=draft.items.filter { it.id!=item.id }).removeEmptyRows());selected=null}) { Icon(Icons.Outlined.DeleteOutline,strings.getString(R.string.ui_remove_1_s, canvasLabel(item,allApps,strings))) }
                        }

                    }
                }
                }
                Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={palette=true},modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text(strings.getString(R.string.ui_add_controls)) }
                    if(draft.emptyRowCount>0 && draft.items.isNotEmpty())TextButton(onClick={update(draft.removeEmptyRows());selected=null},modifier=Modifier.heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_remove_empty_rows),fontSize=12.sp) }
                }
            }
        }
        if(presets)CanvasPresetsDialog(draft,allApps,savedPresets,state.capabilities,hand,vm::setHand,onDismiss={presets=false},apply={value,targets -> applyPreset(value,targets)},save=vm::savePreset,delete=vm::deletePreset)
        if(sizes && item!=null)AlertDialog(containerColor=Panel,onDismissRequest={sizes=false},title={Text(strings.getString(R.string.ui_resize_1_s, canvasLabel(item,allApps,strings)))},text={Column {
            Row(verticalAlignment=Alignment.CenterVertically) { Text(strings.getString(R.string.ui_width),Modifier.weight(1f));IconButton(onClick={change(draft.resizePushingDown(item.id,w=item.w-1))},enabled=item.w>item.kind.minW) { Icon(Icons.Outlined.Remove,strings.getString(R.string.ui_reduce_width)) };Text("${item.w}");IconButton(onClick={change(draft.resizePushingDown(item.id,w=item.w+1))},enabled=item.x+item.w<6) { Icon(Icons.Outlined.Add,strings.getString(R.string.ui_increase_width)) } }
            Row(verticalAlignment=Alignment.CenterVertically) { Text(strings.getString(R.string.ui_height),Modifier.weight(1f));IconButton(onClick={change(draft.resizePushingDown(item.id,h=item.h-1))},enabled=item.h>item.kind.minH) { Icon(Icons.Outlined.Remove,strings.getString(R.string.ui_reduce_height)) };Text("${item.h}");IconButton(onClick={change(draft.resizePushingDown(item.id,h=item.h+1))},enabled=item.y+item.h<24) { Icon(Icons.Outlined.Add,strings.getString(R.string.ui_increase_height)) } }
            notice?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
        }},confirmButton={TextButton(onClick={sizes=false}) { Text(strings.getString(R.string.ui_done)) }})
        if(palette)ModalBottomSheet(onDismissRequest={palette=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Panel) {
            RemoteControlPicker(state.capabilities,allApps,pickerTab,{pickerTab=it},notice,customApp={customApp=true;notice=null}) { kind,command,app -> add(kind,command,app) }
        }
        if(customApp)ShortcutEditor(apps+pendingApps,onDismiss={customApp=false;notice=null},error=notice) { candidate ->
            if(add(CanvasKind.APP,app=candidate.url)) {
                // Keep metadata for Undo as well as currently placed shortcuts; save only used targets on Done.
                val kept=(pendingApps+candidate).distinctBy { it.url }
                pendingTargets=ArrayList(kept.map { ShortcutCodec.encode(listOf(it)) });customApp=false
            }
        }
    }
}
