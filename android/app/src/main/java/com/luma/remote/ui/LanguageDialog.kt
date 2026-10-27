package com.luma.remote.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.luma.remote.R

@Composable internal fun LanguageDialog(dismiss:()->Unit) {
    val strings=LocalContext.current.resources
    val selected=AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
    AlertDialog(containerColor=Panel,onDismissRequest=dismiss,
        title={Text(strings.getString(R.string.ui_language))},
        text={Column {
            listOf("" to strings.getString(R.string.ui_language_system),"en" to "English","ru" to "Русский").forEach { (code,title) ->
                fun choose() {
                    dismiss()
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                }
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(role=Role.RadioButton,onClick=::choose),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=selected==code,onClick=null)
                    Spacer(Modifier.width(12.dp));Text(title)
                }
            }
        }},confirmButton={TextButton(onClick=dismiss) {Text(strings.getString(R.string.ui_done))}})
}
