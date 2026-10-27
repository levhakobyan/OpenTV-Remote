package com.luma.remote.ui.remote

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.protocol.*
import com.luma.remote.ui.*

@Composable internal fun ShortcutEditor(shortcuts:List<AppShortcut>,onDismiss:()->Unit,error:String?=null,save:(AppShortcut)->Unit) {
    val strings=LocalContext.current.resources

    var name by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf(ShortcutIcon.AUTO) }
    var kind by rememberSaveable { mutableStateOf(ShortcutKind.HTTPS) }
    var guide by remember { mutableStateOf(false) }
    val candidate=AppShortcut(name.trim(),link.trim(),icon,kind)
    AlertDialog(containerColor=Panel,onDismissRequest=onDismiss,title={Text(strings.getString(R.string.ui_custom_app))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ShortcutKind.entries.forEach { choice -> FilterChip(selected=kind==choice,onClick={kind=choice},modifier=Modifier.heightIn(min=48.dp),label={Text(strings.label(choice))}) }
        }
        OutlinedTextField(name,{if(it.length<=24)name=it},label={Text(strings.getString(R.string.ui_name))},singleLine=true,modifier=Modifier.fillMaxWidth())
        val fieldLabel=when(kind) { ShortcutKind.HTTPS -> strings.getString(R.string.ui_https_app_link);ShortcutKind.DEEP_LINK -> strings.getString(R.string.ui_app_deep_link);ShortcutKind.PACKAGE -> strings.getString(R.string.ui_tv_app_package_id) }
        val example=when(kind) { ShortcutKind.HTTPS -> "https://www.youtube.com/";ShortcutKind.DEEP_LINK -> "spotify:album:…";ShortcutKind.PACKAGE -> "org.xbmc.kodi" }
        OutlinedTextField(link,{if(it.length<=2048)link=it},label={Text(fieldLabel)},placeholder={Text(example)},singleLine=true,isError=link.isNotBlank() && !candidate.copy(name="Check").isValid(),modifier=Modifier.fillMaxWidth(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Uri))
        if(shortcuts.any { it.url==candidate.url })Text(strings.getString(R.string.ui_this_target_is_already_saved_choose_it_from_apps),fontSize=12.sp,color=Muted)
        if(kind==ShortcutKind.PACKAGE)Text(strings.getString(R.string.ui_experimental_requires_the_installed_tv_app_and_a_compatible_play),fontSize=12.sp,color=Muted)
        TextButton(onClick={guide=!guide},modifier=Modifier.heightIn(min=48.dp)) { Icon(Icons.Outlined.HelpOutline,null);Spacer(Modifier.width(8.dp));Text(if(guide)strings.getString(R.string.ui_hide_guide) else strings.getString(R.string.ui_how_to_set_it_up)) }
        if(guide)Column(Modifier.fillMaxWidth().background(Ink,RoundedCornerShape(16.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(strings.getString(R.string.ui_1_install_the_app_on_your_tv),fontWeight=FontWeight.SemiBold)
            Text(strings.getString(R.string.ui_2_choose_a_target),fontWeight=FontWeight.SemiBold)
            Text(strings.getString(R.string.ui_https_link_paste_the_publisher_s_app_or_content_link_the_tv_may_o),fontSize=13.sp,color=Muted)
            Text(strings.getString(R.string.ui_3_save_then_tap_the_shortcut_to_try_it_you_can_remove_it_from_you),fontSize=13.sp,color=Muted)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ShortcutIcon.entries.forEach { choice -> FilterChip(selected=icon==choice,onClick={icon=choice},modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription=strings.getString(R.string.ui_1_s_shortcut_icon, strings.label(choice)) },label={Icon(shortcutGlyph(choice),null,Modifier.size(22.dp))}) }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
    }},confirmButton={TextButton(onClick={save(candidate)},enabled=candidate.isValid() && shortcuts.none { it.url==candidate.url }) { Text(strings.getString(R.string.ui_add)) }},dismissButton={TextButton(onClick=onDismiss) { Text(strings.getString(R.string.ui_cancel)) }})
}
