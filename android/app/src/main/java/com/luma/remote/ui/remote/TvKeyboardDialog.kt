package com.luma.remote.ui.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luma.remote.R
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*
import com.luma.remote.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun TvKeyboardDialog(vm:RemoteViewModel,onDismiss:()->Unit) {
    val strings=LocalContext.current.resources
    val state by vm.remote.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val ime=state.ime
    val live=ime.active && ime.mode==TvImeMode.LIVE
    // Text and composition are deliberately excluded from saveable state and preferences.
    var value by remember(ime.session) { mutableStateOf(
        ime.value?.takeIf {live || ime.mode==TvImeMode.CONFLICT || ime.clientRevision>0}
            ?.let { TextFieldValue(it.text,TextRange(it.start,it.end)) } ?: TextFieldValue()) }
    var localRevision by remember(ime.session) { mutableLongStateOf(0) }
    var editorBasis by remember(ime.session) { mutableLongStateOf(ime.externalVersion) }
    var privateInput by remember(ime.session) { mutableStateOf(!live) }
    val effectivePrivate=privateInput || ime.mode==TvImeMode.PRIVATE
    val focus=remember(ime.session,editorBasis) { FocusRequester() }
    val scope=rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var entering by remember(ime.session) { mutableStateOf(false) }
    var feedback by remember(ime.session) { mutableStateOf<String?>(null) }
    var hadField by remember { mutableStateOf(ime.active) }
    val close={vm.closeKeyboard(ime.session);onDismiss()}
    LaunchedEffect(live) {if(!live)privateInput=true}
    DisposableEffect(ime.session) { val session=ime.session;onDispose {vm.closeKeyboard(session)} }
    LaunchedEffect(ime.revision,ime.session) {
        if(hadField && !ime.active) { onDismiss();return@LaunchedEffect }
        if(ime.active)hadField=true
        if(live && ime.clientRevision>=localRevision)ime.value?.let { remote ->
            val externalChange=editorBasis!=ime.externalVersion
            editorBasis=ime.externalVersion
            if(externalChange || remote.text!=value.text || remote.start!=value.selection.start || remote.end!=value.selection.end) {
                value=TextFieldValue(remote.text,TextRange(remote.start,remote.end),if(!externalChange && remote.text==value.text)value.composition else null)
            }
        }
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn)) {
        val keyboard=LocalSoftwareKeyboardController.current
        fun sendManual() {
            if(value.text.isEmpty() || value.text.length>1000 || sending)return
            val submitted=value.text;val session=ime.session
            sending=true;feedback=null
            scope.launch {
                try {
                    feedback=if(vm.text(submitted,session))strings.getString(R.string.ui_sent_to_tv_use_its_ok_or_search_action_to_submit)
                        else strings.getString(R.string.ui_could_not_send_text_check_the_connection_and_select_a_tv_text_fie)
                } finally {sending=false}
            }
        }
        fun enter() {
            if(entering)return
            val session=ime.session;val basis=editorBasis
            val commit=value.composition!=null
            if(commit)value=value.copy(composition=null)
            val committed=value
            val revision=if(commit)++localRevision else localRevision
            entering=true
            scope.launch {
                try {
                    if(commit && !vm.keyboardEdit(session,TvImeValue(committed.text,committed.selection.start,committed.selection.end),false,revision,basis))return@launch
                    if(!vm.keyboardEnter(session,revision))feedback=strings.getString(R.string.ui_ime_enter_unavailable)
                } finally {entering=false}
            }
        }
        LaunchedEffect(ime.session,editorBasis) { delay(150);focus.requestFocus();keyboard?.show() }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),contentAlignment=Alignment.BottomCenter) {
            Surface(Modifier.widthIn(max=560.dp).fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Panel) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(strings.getString(R.string.ui_tv_keyboard),Modifier.weight(1f),fontSize=18.sp)
                        IconButton(onClick=close) {Icon(Icons.Outlined.Close,strings.getString(R.string.ui_close_tv_keyboard))}
                    }
                    Text(strings.getString(when {
                        ime.mode==TvImeMode.CONFLICT -> R.string.ui_ime_conflict
                        ime.mode==TvImeMode.PRIVATE -> R.string.ui_ime_private_field
                        live && ime.pending -> R.string.ui_ime_waiting
                        live -> R.string.ui_ime_live
                        else -> R.string.ui_ime_manual
                    }),fontSize=12.sp,color=Muted)
                    if(!live && ime.mode==TvImeMode.MANUAL && ime.clientRevision==0L)ime.value?.let { readback ->
                        Text(strings.getString(R.string.ui_ime_current_tv_field),fontSize=11.sp,color=Muted)
                        Text(readback.text,fontSize=12.sp,color=Muted,maxLines=3)
                    }
                    val renderedValue=value
                    val renderedBasis=editorBasis
                    val renderedSession=ime.session
                    // Restart only for a new field or an unsolicited TV change, never our own ACK.
                    key(renderedSession,renderedBasis) {
                    OutlinedTextField(renderedValue,{ next ->
                        val staleDecoration=renderedBasis!=editorBasis && next.text==renderedValue.text
                        if(next!=renderedValue && !staleDecoration && next.text.length<=(if(live)4096 else 1000)) {
                            value=next;feedback=null
                            if(live) {
                                val revision=++localRevision
                                val session=renderedSession;val basis=renderedBasis
                                scope.launch {vm.keyboardEdit(session,TvImeValue(next.text,next.selection.start,next.selection.end),next.composition!=null,revision,basis)}
                            }
                        }
                    },modifier=Modifier.fillMaxWidth().focusRequester(focus),label={Text(strings.getString(R.string.ui_text))},singleLine=true,
                        visualTransformation=if(!live && effectivePrivate)PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions=KeyboardOptions(autoCorrectEnabled=live || !effectivePrivate,keyboardType=if(!live && effectivePrivate)KeyboardType.Password else KeyboardType.Text,imeAction=if(live)ImeAction.Go else ImeAction.Send),
                        keyboardActions=KeyboardActions(onGo={enter()},onSend={if(live)enter() else sendManual()}),
                        trailingIcon=if(!live){{IconButton(onClick=::sendManual,enabled=value.text.isNotEmpty() && value.text.length<=1000 && !sending && state.phase==Phase.READY) {Icon(Icons.Outlined.Send,strings.getString(R.string.ui_send_text_to_tv))}}} else null)
                    }
                    if(sending || ime.pending)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(feedback!=null)Text(feedback!!,fontSize=12.sp)
                    if(live)Text(strings.getString(R.string.ui_ime_selection_help),fontSize=11.sp,color=Muted)
                    else Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(strings.getString(R.string.ui_ime_private_input),Modifier.weight(1f),fontSize=13.sp)
                        Switch(effectivePrivate,{privateInput=it},enabled=ime.mode!=TvImeMode.PRIVATE)
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={
                            value=TextFieldValue();feedback=null
                            if(live) {val revision=++localRevision;val session=ime.session;val basis=editorBasis;scope.launch {vm.keyboardEdit(session,TvImeValue(""),false,revision,basis)}}
                        },enabled=value.text.isNotEmpty() && !sending) {Text(strings.getString(R.string.ui_clear_text))}
                        if(selected?.platform==Platform.ANDROID_TV && live)TextButton(onClick=::enter,enabled=!entering && state.phase==Phase.READY) {Text(strings.getString(R.string.ui_ime_enter))}
                    }
                }
            }
        }
    }
}
