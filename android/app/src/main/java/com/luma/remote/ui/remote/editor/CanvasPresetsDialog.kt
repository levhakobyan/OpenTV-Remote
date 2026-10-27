package com.luma.remote.ui.remote.editor

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.protocol.*
import com.luma.remote.ui.*

@Composable internal fun CanvasPresetsDialog(draft:RemoteCanvas,apps:List<AppShortcut>,saved:List<CanvasPreset>,caps:Capabilities,hand:HandPreference,onHand:(HandPreference)->Unit,onDismiss:()->Unit,apply:(RemoteCanvas,List<AppShortcut>)->Unit,save:(CanvasPreset)->Boolean,delete:(String)->Unit) {
    val strings=LocalContext.current.resources

    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<CanvasPreset?>(null) }
    var selected by remember { mutableStateOf<CanvasPreset?>(null) }
    val candidates=CanvasTemplate.entries.map { template ->
        template to remember(template,caps,apps,hand) { template.create(caps,apps.take(2),hand) }
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=Ink) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onDismiss) { Icon(Icons.Outlined.Close,strings.getString(R.string.ui_close_presets)) }
                    Text(strings.getString(R.string.ui_presets),Modifier.weight(1f),fontSize=22.sp)
                    val canSave=draft.items.isNotEmpty() && saved.size<CanvasPresetCodec.LIMIT
                    if(LocalDensity.current.fontScale>=1.5f)IconButton(onClick={naming=true;error=null},enabled=canSave) { Icon(Icons.Outlined.BookmarkAdd,strings.getString(R.string.ui_save_current_as_preset)) }
                    else TextButton(onClick={naming=true;error=null},enabled=canSave) { Icon(Icons.Outlined.BookmarkAdd,null);Spacer(Modifier.width(6.dp));Text(strings.getString(R.string.ui_save_current)) }
                }
                Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        HandPreference.entries.forEach { choice -> FilterChip(selected=hand==choice,onClick={onHand(choice)},label={Text(strings.label(choice))},modifier=Modifier.weight(1f).heightIn(min=48.dp)) }
                    }
                    Text(strings.getString(R.string.ui_changes_rocker_sides_in_templates_saved_layouts_keep_their_positi),color=Muted,fontSize=12.sp)
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    val columns=if(maxWidth<340.dp || LocalDensity.current.fontScale>=1.5f)1 else 2
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(saved.isNotEmpty()) {
                            item { Text(strings.getString(R.string.ui_your_presets),color=Accent,fontSize=16.sp) }
                            saved.chunked(columns).forEach { row -> item {
                                Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    row.forEach { preset -> Column(Modifier.weight(1f)) {
                                        val compatible=remember(preset,caps) { preset.forDevice(caps) }
                                        val skipped=preset.canvas.items.size-compatible.items.size
                                        CanvasPresetCard(preset.name,if(skipped>0)strings.getQuantityString(R.plurals.preset_unavailable_count,skipped,skipped) else strings.getString(R.string.ui_your_saved_layout),compatible,Modifier.fillMaxWidth(),onClick={selected=preset})
                                        TextButton(onClick={removing=preset},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(6.dp));Text(strings.getString(R.string.ui_delete_1_s, preset.name),fontSize=12.sp) }
                                    } }
                                    repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            } }
                        }
                        item { Text(strings.getString(R.string.ui_ready_to_use),color=Accent,fontSize=16.sp);Text(strings.getString(R.string.ui_preview_apply_then_adjust_undo_stays_available),color=Muted,fontSize=12.sp) }
                        candidates.chunked(columns).forEach { row -> item {
                            Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                row.forEach { (template,canvas) -> CanvasPresetCard(strings.label(template),strings.description(template),canvas,Modifier.weight(1f).fillMaxHeight(),enabled=canvas.items.isNotEmpty(),onClick={apply(canvas,apps)}) }
                                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        } }
                        item {
                            TextButton(onClick={apply(RemoteCanvas(emptyList(),rows=1),emptyList())},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_blank_canvas)) }
                            if(saved.size>=CanvasPresetCodec.LIMIT)Text(strings.getString(R.string.ui_12_saved_presets_delete_one_to_save_another),color=Muted,fontSize=12.sp)
                        }
                    }
                }
            }
        }
    }
    if(naming)AlertDialog(containerColor=Panel,onDismissRequest={naming=false},title={Text(strings.getString(R.string.ui_save_preset))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name,{if(it.length<=32)name=it;error=null},label={Text(strings.getString(R.string.ui_preset_name))},singleLine=true,modifier=Modifier.fillMaxWidth())
        Text(strings.getString(R.string.ui_saved_on_this_phone_including_app_shortcuts_available_for_all_you),color=Muted,fontSize=12.sp)
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
    }},confirmButton={TextButton(onClick={
        val targets=draft.items.filter { it.kind==CanvasKind.APP }.mapNotNull { item -> apps.firstOrNull { it.url==item.app } }.distinctBy { it.url }
        if(save(CanvasPreset(name=name.trim(),canvas=draft.compacted(),apps=targets))) { naming=false;name="" }
        else error=strings.getString(R.string.ui_use_a_unique_name_up_to_32_characters_and_no_more_than_12_presets)
    },enabled=name.isNotBlank()) { Text(strings.getString(R.string.ui_save)) }},dismissButton={TextButton(onClick={naming=false}) { Text(strings.getString(R.string.ui_cancel)) }})
    removing?.let { preset -> AlertDialog(containerColor=Panel,onDismissRequest={removing=null},title={Text(strings.getString(R.string.ui_delete_1_s_261, preset.name))},text={Text(strings.getString(R.string.ui_your_current_remote_stays_as_it_is))},confirmButton={TextButton(onClick={delete(preset.id);removing=null}) { Text(strings.getString(R.string.ui_delete)) }},dismissButton={TextButton(onClick={removing=null}) { Text(strings.getString(R.string.ui_cancel)) }}) }
    selected?.let { preset ->
        val compatible=preset.forDevice(caps)
        val skipped=preset.canvas.items.size-compatible.items.size
        if(skipped==0)SideEffect { selected=null;apply(compatible,preset.apps) }
        else AlertDialog(containerColor=Panel,onDismissRequest={selected=null},title={Text(preset.name)},text={Text(strings.getQuantityString(R.plurals.preset_skipped_controls,skipped,skipped))},confirmButton={TextButton(onClick={selected=null;apply(compatible,preset.apps)},enabled=compatible.items.isNotEmpty()) { Text(strings.getString(R.string.ui_apply_compatible_controls)) }},dismissButton={TextButton(onClick={selected=null}) { Text(strings.getString(R.string.ui_cancel)) }})
    }
}
