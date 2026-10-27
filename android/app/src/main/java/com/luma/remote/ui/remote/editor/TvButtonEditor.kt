package com.luma.remote.ui.remote.editor

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*
import com.luma.remote.ui.*

@Composable internal fun TvButtonEditor(vm:RemoteViewModel,layout:RemoteLayout,caps:Capabilities,shortcuts:List<AppShortcut>) {
    val strings=LocalContext.current.resources

    Text(strings.getString(R.string.ui_tv_button),fontWeight=FontWeight.SemiBold)
    Text(strings.getString(R.string.ui_try_a_key_and_keep_the_one_that_opens_your_channels_the_tv_does_n),color=Muted,fontSize=12.sp)
    val options=TvAction.entries.filter { it==TvAction.HIDDEN || it==TvAction.APP && caps.apps || it.command in caps.commands }
    options.chunked(2).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        row.forEach { action -> FilterChip(selected=layout.tvAction==action,onClick={vm.saveLayout(layout.copy(tvAction=action,tvConfigured=true))},modifier=Modifier.weight(1f).heightIn(min=48.dp),label={Text(strings.label(action))}) }
        if(row.size==1)Spacer(Modifier.weight(1f))
    } }
    if(layout.tvAction==TvAction.APP) {
        if(shortcuts.isEmpty())Text(strings.getString(R.string.ui_add_an_app_shortcut_first),color=Muted)
        shortcuts.forEach { app -> FilterChip(selected=layout.tvApp==app.url,onClick={vm.saveLayout(layout.copy(tvApp=app.url,tvConfigured=true))},modifier=Modifier.heightIn(min=48.dp),label={Text(app.name)},leadingIcon={ShortcutBadge(app,Modifier.size(20.dp))}) }
    }
    val canTest=layout.tvAction.command in caps.commands || layout.tvAction==TvAction.APP && caps.apps && shortcuts.any { it.url==layout.tvApp }
    OutlinedButton(onClick={vm.saveLayout(layout.copy(tvConfigured=true));vm.tvAction()},enabled=canTest,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(Icons.Outlined.Tv,null);Spacer(Modifier.width(8.dp));Text(strings.getString(R.string.ui_try_on_tv)) }
 }
