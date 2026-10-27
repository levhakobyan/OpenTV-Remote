package com.luma.remote.ui

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.ui.theme.*

@Composable internal fun AppearanceDialog(initial:String,onDismiss:()->Unit,save:(String)->Unit) {
    val strings=LocalContext.current.resources

    var draft by rememberSaveable { mutableStateOf(initial) }
    var custom by rememberSaveable { mutableStateOf(AccentChoice.entries.none { it.hex==initial }) }
    var picker by rememberSaveable { mutableStateOf(false) }
    LumaTheme(draft) {
        AlertDialog(containerColor=Ink,onDismissRequest=onDismiss,title={Text(strings.getString(R.string.ui_appearance))},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(strings.getString(R.string.ui_your_color_stays_when_you_change_layouts),color=Muted,fontSize=13.sp)
                // Visual sample only: this preview has no remote or transport handlers.
                Surface(Modifier.fillMaxWidth(),color=Panel,shape=RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) { LumaMark(Modifier.size(28.dp));Text(strings.getString(R.string.ui_your_remote),Modifier.weight(1f).padding(start=8.dp),color=MaterialTheme.colorScheme.onSurface);Icon(Icons.Outlined.PowerSettingsNew,null,tint=Accent) }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(Icons.Outlined.KeyboardArrowLeft,Icons.Outlined.Check,Icons.Outlined.KeyboardArrowRight).forEachIndexed { index,icon ->
                                Box(Modifier.weight(1f).height(56.dp).background(if(index==1)Accent else Raised,RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center) { Icon(icon,null,tint=if(index==1)Ink else MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    }
                }
                val columns=if(LocalDensity.current.fontScale>=1.5f)2 else 3
                AccentChoice.entries.chunked(columns).forEach { row -> Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { choice ->
                        val selected=!custom && draft==choice.hex
                        Surface(onClick={draft=choice.hex;custom=false},modifier=Modifier.weight(1f).fillMaxHeight().heightIn(min=84.dp).semantics { contentDescription=strings.getString(R.string.ui_1_s_accent, strings.label(choice)) },shape=RoundedCornerShape(18.dp),color=Panel,border=if(selected)androidx.compose.foundation.BorderStroke(2.dp,Accent) else null) {
                            Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(28.dp).background(Color(AccentPalette.rgb(choice.hex)),CircleShape),contentAlignment=Alignment.Center) { if(selected)Icon(Icons.Outlined.Check,null,Modifier.size(18.dp),tint=Color(0xFF101214)) }
                                Text(strings.label(choice),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                } }
                FilterChip(selected=custom,onClick={picker=true},label={Text(strings.getString(R.string.ui_custom_color))},leadingIcon={Icon(Icons.Outlined.ColorLens,null)},modifier=Modifier.heightIn(min=48.dp))
                if(custom)TextButton(onClick={picker=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_edit_custom_color_1_s, draft)) }

            }
        },confirmButton={TextButton(onClick={save(draft)}) { Text(strings.getString(R.string.ui_use_color)) }},dismissButton={TextButton(onClick=onDismiss) { Text(strings.getString(R.string.ui_cancel)) }})
        if(picker)CustomColorPicker(draft,onDismiss={picker=false},choose={value -> draft=value;custom=true;picker=false})
    }
}
