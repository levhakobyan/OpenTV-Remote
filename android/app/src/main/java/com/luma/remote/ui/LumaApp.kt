package com.luma.remote.ui

import com.luma.remote.R
import com.luma.remote.ui.remote.RemoteScreen

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LumaApp(vm:RemoteViewModel) {
    val strings=LocalContext.current.resources

    val welcomed by vm.welcomed.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val state by vm.remote.collectAsStateWithLifecycle()
    val compactRemote=welcomed && page==0 && selected!=null
    val snackbar=remember { SnackbarHostState() }
    val accentHex by vm.accentHex.collectAsStateWithLifecycle()
    var appearance by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(false) }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.notice.value=null } }
    BackHandler(enabled=welcomed && selected!=null && page!=0) { vm.page.value=0 }
    LumaTheme(accentHex) {
        Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),containerColor=Ink,snackbarHost={ SnackbarHost(snackbar) },bottomBar={ if(welcomed && !compactRemote) NavigationBar(modifier=Modifier.height(64.dp),containerColor=Ink,tonalElevation=0.dp,windowInsets=WindowInsets(0,0,0,0)) {
            listOf(strings.getString(R.string.ui_remote) to Icons.Outlined.SettingsRemote,strings.getString(R.string.ui_devices) to Icons.Outlined.Tv).forEachIndexed { i,(label,icon) -> NavigationBarItem(selected=page==i,onClick={vm.page.value=i},icon={Icon(icon,null)},label={Text(label)},colors=NavigationBarItemDefaults.colors(indicatorColor=Raised,selectedIconColor=Accent,selectedTextColor=Accent)) }
        } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(!compactRemote)Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
                    LumaMark(Modifier.size(28.dp))
                    Text("luma",Modifier.padding(start=8.dp).weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold,letterSpacing=(-0.5).sp)
                    IconButton(onClick={language=true}) { Icon(Icons.Outlined.Language,strings.getString(R.string.ui_language),tint=Muted) }
                    IconButton(onClick={appearance=true}) { Icon(Icons.Outlined.Palette,strings.getString(R.string.ui_appearance),tint=Accent) }
                    IconButton(onClick={info=true}) { Icon(Icons.Outlined.Info,strings.getString(R.string.ui_compatibility_privacy),tint=Muted) }
                }
                Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.TopCenter) {
                    Box(Modifier.widthIn(max=600.dp).fillMaxSize()) {
                        if(!welcomed) Welcome(vm::begin)
                        else when(page) { 0 -> RemoteScreen(vm,selected,state,onInfo={info=true},onAppearance={appearance=true},onLanguage={language=true}); else -> DeviceScreen(vm) }
                    }
                }
            }
        }
        LaunchedEffect(appearance,info,language) { if(appearance || info || language)vm.stopHolds() }
        if(language)LanguageDialog {language=false}
        if(appearance)AppearanceDialog(accentHex,onDismiss={appearance=false},save={vm.setAccent(it);appearance=false})
        if(info) AlertDialog(containerColor=Panel,onDismissRequest={info=false},title={Text(strings.getString(R.string.ui_about_luma))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick={info=false;language=true}) {Icon(Icons.Outlined.Language,null);Spacer(Modifier.width(8.dp));Text(strings.getString(R.string.ui_language))}
            Text(strings.getString(R.string.ui_android_tv_wi_fi_control_reported_working_by_the_user_new_shortcu))
            Text(strings.getString(R.string.ui_bluetooth_android_9_hid_mouse_keyboard_where_supported_touchpad_g))
            Text(strings.getString(R.string.ui_roku_wi_fi_control_is_restricted_by_roku_samsung_local_wi_fi_need))
            Text(strings.getString(R.string.ui_pairing_secrets_stay_encrypted_on_your_phone_no_account_ads_or_an))
            Row(verticalAlignment=Alignment.CenterVertically) { Text(strings.getString(R.string.ui_touch_feedback),Modifier.weight(1f)); var enabled by remember { mutableStateOf(vm.haptics) }; Switch(enabled,{enabled=it;vm.haptics=it}) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text(strings.getString(R.string.ui_auto_keyboard),Modifier.weight(1f)); var enabled by remember { mutableStateOf(vm.autoKeyboard) }; Switch(enabled,{enabled=it;vm.autoKeyboard=it}) }
        }},confirmButton={TextButton(onClick={info=false}) { Text(strings.getString(R.string.ui_got_it)) }})
    }
}
@Composable private fun Eyebrow(text:String) { Text(text.uppercase(),color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp) }
@Composable private fun Welcome(start:()->Unit) {
    val strings=LocalContext.current.resources

    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            LumaMark(Modifier.padding(top=20.dp).size(100.dp))
            Text(strings.getString(R.string.ui_your_tv_nwithin_reach),fontSize=32.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold)
            Text(strings.getString(R.string.ui_connect_to_your_tv_over_home_wi_fi_no_account_needed),color=Muted,fontSize=16.sp,lineHeight=24.sp)
        }
        Button(onClick=start,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(16.dp)) { Text(strings.getString(R.string.ui_find_my_tv),fontSize=17.sp); Spacer(Modifier.width(12.dp)); Icon(Icons.Outlined.ArrowForward,null) }
    }
}
@Composable internal fun Pairing(vm:RemoteViewModel) {
    val strings=LocalContext.current.resources
    val selected by vm.selected.collectAsStateWithLifecycle()
    var code by rememberSaveable(selected?.id) { mutableStateOf("") }; val busy by vm.pairingBusy.collectAsStateWithLifecycle()
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) { Text(strings.getString(R.string.ui_pair_your_tv),fontSize=24.sp,fontWeight=FontWeight.Medium); Text(strings.getString(R.string.ui_enter_the_code_shown_on_your_tv),color=Muted)
        OutlinedTextField(code,{if(it.length<=6)code=it.uppercase().filter { c -> c in "0123456789ABCDEF" }},label={Text(strings.getString(R.string.ui_tv_pairing_code))},supportingText={Text(strings.getString(R.string.ui_6_characters_0_9_and_a_f))},singleLine=true,keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Characters,keyboardType=androidx.compose.ui.text.input.KeyboardType.Ascii),modifier=Modifier.fillMaxWidth())
        Button(onClick={vm.pair(code)},enabled=code.length==6 && !busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(if(busy) strings.getString(R.string.ui_pairing) else strings.getString(R.string.ui_pair_securely)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeviceScreen(vm:RemoteViewModel) {
    val strings=LocalContext.current.resources

    val devices by vm.discovery.devices.collectAsStateWithLifecycle(); val saved by vm.saved.collectAsStateWithLifecycle(); val scanning by vm.discovery.scanning.collectAsStateWithLifecycle(); val issue by vm.discovery.issue.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }; var bluetooth by remember { mutableStateOf(false) }; var btDevices by remember { mutableStateOf<List<TvDevice>>(emptyList()) }; var consent by remember { mutableStateOf<TvDevice?>(null) }
    val context=LocalContext.current
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> if(grants.isNotEmpty() && grants.values.all { it }) { bluetooth=true; btDevices=runCatching { vm.bonded() }.getOrDefault(emptyList()) } else vm.notice.value=strings.getString(R.string.ui_bluetooth_permission_was_denied_you_can_still_connect_over_wi_fi) }
    fun bluetoothOpen() {
        if(Build.VERSION.SDK_INT>=31 && (ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED))permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE))
        else { bluetooth=true; btDevices=runCatching { vm.bonded() }.getOrDefault(emptyList()) }
    }
    fun select(tv:TvDevice) { if(tv.platform==Platform.LG)consent=tv else vm.connect(tv) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(strings.getString(R.string.ui_devices),fontSize=24.sp,fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) { AssistChip(onClick={context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))},label={Text(strings.getString(R.string.ui_same_wi_fi_network))},leadingIcon={Icon(Icons.Outlined.Wifi,null,Modifier.size(18.dp))}); if(scanning)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) }
        if(saved.isNotEmpty()) { Eyebrow(strings.getString(R.string.ui_saved_devices)); saved.forEach { tv -> DeviceCard(tv,onClick={select(tv)},forget={vm.forget(tv)}) } }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(strings.getString(R.string.ui_nearby),Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.Medium); TextButton(onClick={vm.discovery.start()},enabled=!scanning) { Icon(Icons.Outlined.Refresh,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if(scanning)strings.getString(R.string.ui_searching) else strings.getString(R.string.ui_scan_again)) } }
        val nearby=devices.filter { d -> saved.none { it.id==d.id } }
        if(nearby.isEmpty())Surface(shape=RoundedCornerShape(28.dp),color=Panel) { Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Radar,null,Modifier.size(36.dp),tint=Accent)
            Text(if(scanning)strings.getString(R.string.ui_searching) else strings.getString(R.string.ui_no_tvs_found),fontSize=20.sp,fontWeight=FontWeight.Medium)
            Text(if(scanning)strings.getString(R.string.ui_keep_your_tv_on) else strings.getString(R.string.ui_check_that_your_tv_is_on_the_same_wi_fi),color=Muted,fontSize=14.sp,lineHeight=21.sp)
        } } else nearby.forEach { tv -> DeviceCard(tv,onClick={select(tv)}) }
        issue?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=14.sp) }
        HorizontalDivider(color=Raised)
        Text(strings.getString(R.string.ui_other_connections),fontSize=16.sp,fontWeight=FontWeight.Medium)
        OutlinedButton(onClick={bluetoothOpen()},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(16.dp)) { Icon(Icons.Outlined.Bluetooth,null); Spacer(Modifier.width(12.dp)); Text(strings.getString(R.string.ui_bluetooth_remote)) }
        TextButton(onClick={manual=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(strings.getString(R.string.ui_connect_by_ip)); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward,null,Modifier.size(18.dp)) }
    }
    consent?.let { tv -> AlertDialog(containerColor=Panel,onDismissRequest={consent=null},title={Text(strings.getString(R.string.ui_connect_to_1_s, tv.name))},text={Text(strings.getString(R.string.ui_this_lg_provider_uses_legacy_unencrypted_local_port_3000_use_a_tr))},confirmButton={TextButton(onClick={consent=null;vm.connect(tv)}) { Text(strings.getString(R.string.ui_connect_on_trusted_wi_fi)) }},dismissButton={TextButton(onClick={consent=null}) { Text(strings.getString(R.string.ui_cancel)) }}) }
    if(manual) { var host by rememberSaveable() { mutableStateOf("") }; AlertDialog(containerColor=Panel,onDismissRequest={manual=false},title={Text(strings.getString(R.string.ui_connect_by_ip))},text={Column(verticalArrangement=Arrangement.spacedBy(16.dp)) { Text(strings.getString(R.string.ui_find_the_tv_s_local_ipv4_address_in_its_network_settings_we_ll_ch),color=Muted); OutlinedTextField(host,{host=it},label={Text(strings.getString(R.string.ui_tv_ip_address))},placeholder={Text("192.168.1.20")},singleLine=true,modifier=Modifier.fillMaxWidth()) }},confirmButton={TextButton(onClick={vm.manual(host);manual=false},enabled=host.isNotBlank()) { Text(strings.getString(R.string.ui_check_device)) }},dismissButton={TextButton(onClick={manual=false}) { Text(strings.getString(R.string.ui_cancel)) }}) }
    if(bluetooth)ModalBottomSheet(onDismissRequest={bluetooth=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Panel) {
        val setup by vm.bluetoothSetup.collectAsStateWithLifecycle()
        var mouse by remember { mutableStateOf(vm.bluetoothMouse) }
        DisposableEffect(mouse) { vm.prepareBluetooth();onDispose { vm.endBluetoothSetup() } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(strings.getString(R.string.ui_a_mouse_for_your_tv),fontSize=27.sp,fontWeight=FontWeight.Medium)
            Text(strings.getString(R.string.ui_1_enable_bluetooth_on_both_devices_n2_make_this_phone_discoverabl),color=Muted,lineHeight=23.sp)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(strings.getString(R.string.ui_mouse_keyboard));Text(strings.getString(R.string.ui_turn_off_if_the_tv_only_accepts_keyboards),fontSize=12.sp,color=Muted) }
                Switch(checked=mouse,onCheckedChange={mouse=it;vm.bluetoothMouse=it},modifier=Modifier.semantics { contentDescription=strings.getString(R.string.ui_bluetooth_mouse_mode) })
            }
            Text(strings.getString(setup.messageRes),color=if(setup.phase==Phase.ERROR)MaterialTheme.colorScheme.error else Muted,fontSize=13.sp)
            if(setup.phase==Phase.ERROR)TextButton(onClick=vm::prepareBluetooth) { Text(strings.getString(R.string.ui_retry_hid_setup)) }
            Button(onClick={runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120)) }.onFailure { vm.notice.value=strings.getString(R.string.ui_open_bluetooth_settings_to_make_this_phone_discoverable) }},enabled=setup.phase==Phase.PAIRING,modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.ui_make_phone_discoverable)) }
            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))},modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.ui_open_bluetooth_settings)) }
            TextButton(onClick={btDevices=runCatching { vm.bonded() }.getOrDefault(emptyList())},modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.ui_refresh_paired_devices)) }
            if(btDevices.isEmpty())Text(strings.getString(R.string.ui_no_paired_devices_yet_pair_with_your_tv_return_here_then_refresh),color=Muted)
            btDevices.forEach { tv -> DeviceCard(tv,onClick={bluetooth=false;vm.connect(tv)}) }
            Text(strings.getString(R.string.ui_move_one_finger_to_point_tap_to_click_or_scroll_with_two_fingers),color=Muted,fontSize=12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable private fun DeviceCard(tv:TvDevice,onClick:()->Unit,forget:(()->Unit)?=null) {
    val strings=LocalContext.current.resources

    Surface(onClick=onClick,shape=RoundedCornerShape(22.dp),color=Panel,modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).background(Raised,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) { Icon(if(tv.platform==Platform.BLUETOOTH)Icons.Outlined.Bluetooth else Icons.Outlined.Tv,null,tint=Accent) }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(tv.name,fontWeight=FontWeight.Medium); Text(strings.label(tv.platform),color=Muted,fontSize=12.sp); Text(if(tv.platform in setOf(Platform.ANDROID_TV,Platform.LG,Platform.SAMSUNG,Platform.BLUETOOTH))tv.host else strings.getString(R.string.ui_view_compatibility),color=Muted,fontSize=12.sp) }
        if(forget!=null)IconButton(onClick=forget) { Icon(Icons.Outlined.DeleteOutline,strings.getString(R.string.ui_forget_1_s, tv.name),tint=Muted) } else Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
    } }
}
