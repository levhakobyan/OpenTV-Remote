package com.luma.remote

import android.app.Application
import android.net.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luma.remote.data.Vault
import com.luma.remote.ui.theme.AccentPalette
import com.luma.remote.ui.theme.AccentChoice
import com.luma.remote.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket

class RemoteViewModel(app:Application):AndroidViewModel(app) {
    private val strings get()=getApplication<Application>().localizedResources()
    val vault=Vault(app)
    val discovery=Discovery(app)
    private val ui=app.getSharedPreferences("ui",0)
    val accentHex=MutableStateFlow(AccentPalette.normalize(ui.getString("accent-color",null)) ?: AccentChoice.LIME.hex)
    fun setAccent(raw:String) {
        val hex=AccentPalette.normalize(raw) ?: return
        ui.edit().putString("accent-color",hex).apply();accentHex.value=hex
    }
    val hand=MutableStateFlow(HandPreference.entries.firstOrNull { it.name==ui.getString("hand-preference",null) } ?: HandPreference.RIGHT)
    fun setHand(value:HandPreference) { ui.edit().putString("hand-preference",value.name).apply();hand.value=value }
    init { if(ui.getString("last-tv",null)?.startsWith("companion:")==true)ui.edit().remove("last-tv").apply() }
    val saved=MutableStateFlow(vault.saved())
    val selected=MutableStateFlow(saved.value.firstOrNull { it.id==ui.getString("last-tv",null) } ?: saved.value.singleOrNull())
    val shortcuts=MutableStateFlow(selected.value?.let { vault.shortcuts(it.id) } ?: emptyList())
    val presets=MutableStateFlow(vault.presets())
    val canvas=MutableStateFlow(selected.value?.let { vault.canvas(it.id) })
    val layout=MutableStateFlow(selected.value?.let { vault.layout(it.id) } ?: RemoteLayout())
    val remote=MutableStateFlow(RemoteState())
    val notice=MutableStateFlow<String?>(null)
    val pairingBusy=MutableStateFlow(false)
    val page=MutableStateFlow(0)
    val welcomed=MutableStateFlow(app.getSharedPreferences("ui",0).getBoolean("welcomed",false))
    var haptics:Boolean
        get()=ui.getBoolean("haptics",true)
        set(value) { ui.edit().putBoolean("haptics",value).apply() }
    var autoKeyboard:Boolean
        get()=ui.getBoolean("auto-keyboard",true)
        set(value) { ui.edit().putBoolean("auto-keyboard",value).apply() }
    val bluetoothSetup=MutableStateFlow(RemoteState())
    private var setupProvider:BluetoothProvider?=null
    private var setupObservation:Job?=null
    var bluetoothMouse:Boolean
        get()=ui.getBoolean("bluetooth-mouse",true)
        set(value) { ui.edit().putBoolean("bluetooth-mouse",value).apply() }
    fun prepareBluetooth() {
        endBluetoothSetup()
        val p=BluetoothProvider(getApplication(),bluetoothMouse);setupProvider=p
        setupObservation=viewModelScope.launch { p.state.collect { bluetoothSetup.value=it } }
        runCatching { p.preparePairing() }.onFailure { bluetoothSetup.value=RemoteState(Phase.ERROR,"Bluetooth is unavailable or permission was revoked. Check Nearby devices permission and Bluetooth settings.",messageRes=R.string.status_bluetooth_is_unavailable_or_permission_was_revoked_check_nearby_d) }
    }
    fun endBluetoothSetup() { setupObservation?.cancel();setupObservation=null;setupProvider?.disconnect();setupProvider=null }
    private var provider:RemoteProvider?=null
    private var connection:Job?=null
    private var observation:Job?=null
    private var retry:Job?=null
    private var foreground=true
    private val holds=HoldRepeater(viewModelScope)
    private var motionToken:Any?=null
    private var motionJob:Job?=null
    private data class Request(val owner:RemoteProvider,val command:Command?=null,val app:AppShortcut?=null,val click:Boolean=false)
    private val commands=Channel<Request>(8)
    private val cm=app.getSystemService(ConnectivityManager::class.java)
    private val network=object:ConnectivityManager.NetworkCallback() {
        override fun onLost(network:Network) { viewModelScope.launch { if(foreground && selected.value?.platform!=Platform.BLUETOOTH && selected.value!=null) { holds.stop();stopMotion(); provider?.disconnect(); remote.value=RemoteState(Phase.ERROR,"Network changed. Waiting to reconnect…",messageRes=R.string.status_network_changed_waiting_to_reconnect) } } }
        override fun onAvailable(network:Network) { viewModelScope.launch { delay(800); if(foreground && selected.value!=null && selected.value?.platform!=Platform.BLUETOOTH && remote.value.phase in setOf(Phase.ERROR,Phase.DISCONNECTED))reconnect() } }
    }
    init {
        cm.registerDefaultNetworkCallback(network)
        viewModelScope.launch { discovery.devices.collect { found -> saved.value.forEach { old -> found.firstOrNull { it.id==old.id && it.host!=old.host }?.let { fresh -> vault.save(fresh); saved.value=vault.saved(); if(selected.value?.id==fresh.id && foreground)connect(fresh) } } } }
        viewModelScope.launch {
            for(request in commands) {
                if(provider!==request.owner || remote.value.phase!=Phase.READY)continue
                try {
                    if(request.click && remote.value.capabilities.pointer)request.owner.click()
                    request.command?.let { if(it in remote.value.capabilities.commands)request.owner.command(it) }
                    request.app?.let { if(remote.value.capabilities.apps)request.owner.launchApp(it) }
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { notice.value=strings.getString(R.string.status_could_not_send_check_the_tv_connection) }
            }
        }
    }
    fun begin() { getApplication<Application>().getSharedPreferences("ui",0).edit().putBoolean("welcomed",true).apply(); welcomed.value=true; page.value=1; discovery.start() }
    fun connect(candidate:TvDevice) {
        val tv=candidate
        if(tv.platform !in setOf(Platform.ANDROID_TV,Platform.LG,Platform.SAMSUNG,Platform.BLUETOOTH)) { notice.value=strings.getString(limitation(tv.platform)); return }
        holds.stop();stopMotion(); retry?.cancel(); connection?.cancel(); observation?.cancel(); provider?.disconnect()
        while(commands.tryReceive().isSuccess) { }
        selected.value=tv; shortcuts.value=vault.shortcuts(tv.id); layout.value=vault.layout(tv.id); canvas.value=vault.canvas(tv.id); page.value=0
        val p=when(tv.platform) { Platform.ANDROID_TV -> AndroidTvProvider(vault); Platform.LG -> WebOsProvider(vault); Platform.SAMSUNG -> SamsungProvider(vault); else -> (setupProvider ?: BluetoothProvider(getApplication(),bluetoothMouse)).also { setupProvider=null;setupObservation?.cancel();setupObservation=null } }
        provider=p
        observation=viewModelScope.launch {
            var wasReady=false
            p.state.collect { state ->
                remote.value=state
                if(state.phase!=Phase.READY) { holds.stop();stopMotion() }
                if(state.phase==Phase.READY && !wasReady) { wasReady=true; vault.save(tv); saved.value=vault.saved(); ui.edit().putString("last-tv",tv.id).apply() }
                if(state.phase==Phase.ERROR && state.retryable && wasReady && foreground && tv.platform!=Platform.BLUETOOTH) {
                    wasReady=false
                    retry?.cancel(); retry=viewModelScope.launch { delay(2000); if(foreground && provider===p) connect(tv) }
                }
            }
        }
        connection=viewModelScope.launch { try { p.connect(tv) } catch(e:CancellationException) { throw e } catch(_:Exception) { remote.value=RemoteState(Phase.ERROR,"Could not connect. Check device permissions, pairing and network, then retry.",messageRes=R.string.status_could_not_connect_check_device_permissions_pairing_and_network_th) } }
    }
    fun trustTvIdentity() {
        val fingerprint=remote.value.identity?.fingerprint ?: return
        runCatching { provider?.trustIdentity(fingerprint) }.onSuccess { reconnect() }.onFailure { notice.value=strings.getString(R.string.status_could_not_save_the_tv_identity_try_again) }
    }
    fun useSecureSamsung() {
        if(selected.value?.platform!=Platform.SAMSUNG)return
        runCatching { provider?.useSecureConnection() }.onSuccess { reconnect() }.onFailure { notice.value=strings.getString(R.string.status_could_not_switch_this_connection_try_again) }
    }
    fun resetSamsungPairing() {
        if(!remote.value.canResetPairing)return
        runCatching { provider?.resetPairing() }.onSuccess { reconnect() }.onFailure { notice.value=strings.getString(R.string.status_could_not_reset_pairing_try_again) }
    }
    fun useLegacySamsung() {
        if(!remote.value.legacyAvailable)return
        runCatching { provider?.useLegacyConnection() }.onSuccess { reconnect() }.onFailure { notice.value=strings.getString(R.string.status_could_not_save_this_connection_choice_try_again) }
    }
    fun cancelConnectionSelection() {
        holds.stop();stopMotion();retry?.cancel();connection?.cancel();observation?.cancel();provider?.disconnect();provider=null
        while(commands.tryReceive().isSuccess) { }
        selected.value=null;shortcuts.value=emptyList();layout.value=RemoteLayout();canvas.value=null;remote.value=RemoteState();page.value=1
    }
    fun reconnect() { selected.value?.let(::connect) }
    fun send(command:Command) {
        val p=provider ?: return
        if(remote.value.phase!=Phase.READY || command !in remote.value.capabilities.commands)return
        if(!commands.trySend(Request(p,command=command)).isSuccess)notice.value=strings.getString(R.string.status_please_wait_for_the_previous_command)
    }
    fun startHold(token:Any,command:Command) {
        if(command !in pressCommands)return
        val p=provider ?: return
        if(p.nativeHold(command)) {
            holds.startNative(token,{foreground && provider===p && remote.value.phase==Phase.READY && command in remote.value.capabilities.commands},
                begin={p.beginHold(command)},end={p.endHold(command)},onError={if(provider===p && remote.value.phase==Phase.READY)notice.value=strings.getString(R.string.status_could_not_send_check_the_tv_connection)})
            return
        }
        holds.start(token,{foreground && provider===p && remote.value.phase==Phase.READY && command in remote.value.capabilities.commands},repeatInterval=if(command in setOf(Command.REWIND,Command.FORWARD))250 else 130,repeat=command in repeatingCommands) {
            try { p.command(command) }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { holds.stop(token); notice.value=strings.getString(R.string.status_could_not_send_check_the_tv_connection) }
        }
    }
    fun startMotion(token:Any) { stopMotion();motionToken=token }
    fun stopMotion(token:Any?=null) {
        if(token!=null && motionToken!==token)return
        motionToken=null;motionJob?.cancel();motionJob=null
    }
    fun motion(token:Any,step:MotionStep) {
        val p=provider ?: return
        if(motionToken!==token || motionJob?.isActive==true || !foreground || remote.value.phase!=Phase.READY)return
        motionJob=viewModelScope.launch {
            try {
                if(motionToken!==token || provider!==p || !foreground)return@launch
                if(step.key!=null && step.key in remote.value.capabilities.commands)p.command(step.key)
                else if(step.scroll!=0f && remote.value.capabilities.scroll)p.scroll(step.scroll)
                else if(step.key==null && remote.value.capabilities.pointer)p.move(step.dx,step.dy)
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { stopMotion(token);notice.value=strings.getString(R.string.status_motion_control_disconnected) }
        }
    }
    fun click() {
        val p=provider ?: return
        if(remote.value.phase!=Phase.READY || !remote.value.capabilities.pointer)return
        if(!commands.trySend(Request(p,click=true)).isSuccess)notice.value=strings.getString(R.string.status_please_wait_for_the_previous_command)
    }
    fun stopHold(token:Any) { holds.stop(token) }
    fun stopHolds() { holds.stop() }
    fun launchApp(shortcut:AppShortcut) {
        val p=provider ?: return
        if(remote.value.phase!=Phase.READY || !remote.value.capabilities.apps || !shortcut.isValid())return
        if(!commands.trySend(Request(p,app=shortcut)).isSuccess)notice.value=strings.getString(R.string.status_please_wait_for_the_previous_command)
    }
    fun savePreset(preset:CanvasPreset):Boolean {
        if(!preset.valid() || presets.value.size>=CanvasPresetCodec.LIMIT || presets.value.any { it.name.equals(preset.name,true) })return false
        val next=presets.value+preset;vault.savePresets(next);presets.value=next;return true
    }
    fun deletePreset(id:String) { val next=presets.value.filter { it.id!=id };vault.savePresets(next);presets.value=next }
    fun saveCanvas(value:RemoteCanvas) {
        val tv=selected.value ?: return
        if(!value.valid())return
        val compact=value.compacted();canvas.value=compact;vault.saveCanvas(tv.id,compact)
    }
    fun saveLayout(value:RemoteLayout) {
        val tv=selected.value ?: return
        layout.value=value.normalized();vault.saveLayout(tv.id,layout.value)
    }
    fun tvAction() {
        val config=layout.value
        if(config.tvAction==TvAction.APP)shortcuts.value.firstOrNull { it.url==config.tvApp }?.let(::launchApp)
        else config.tvAction.command?.let(::send)
    }
    fun saveShortcuts(items:List<AppShortcut>) {
        val tv=selected.value ?: return
        if(items.size>8 || items.any { !it.isValid() })return
        val unique=items.distinctBy { it.url };vault.saveShortcuts(tv.id,unique); shortcuts.value=unique
    }
    fun pair(code:String) { if(pairingBusy.value)return; pairingBusy.value=true; viewModelScope.launch { try { provider?.pair(code) } catch(e:CancellationException) { throw e } catch(_:IllegalArgumentException) { notice.value=strings.getString(R.string.status_code_does_not_match_enter_the_six_characters_shown_on_this_tv_0_9) } catch(_:Exception) { notice.value=strings.getString(R.string.status_pairing_expired_or_was_rejected_reconnect_to_try_again) } finally { pairingBusy.value=false } } }
    suspend fun text(value:String,session:Long?=null):Boolean {
        val p=provider ?: return false
        if(remote.value.phase!=Phase.READY || !remote.value.capabilities.text)return false
        if(session!=null && remote.value.ime.session!=session)return false
        return try {
            if(provider!==p)return false
            if(session!=null && selected.value?.platform==Platform.ANDROID_TV) {
                if(!p.keyboardManual(session,value))return false
            } else p.text(value)
            provider===p && remote.value.phase==Phase.READY
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { false }
    }
    suspend fun keyboardEdit(session:Long,value:TvImeValue,composing:Boolean,revision:Long,baseVersion:Long):Boolean {
        val p=provider ?: return false
        if(!foreground || remote.value.phase!=Phase.READY || selected.value?.platform!=Platform.ANDROID_TV)return false
        return try { if(provider!==p)return false;p.keyboardEdit(session,value,composing,revision,baseVersion) }
        catch(e:CancellationException) {throw e} catch(_:Exception) {false}
    }
    suspend fun keyboardEnter(session:Long,revision:Long):Boolean {
        val p=provider ?: return false
        if(!foreground || remote.value.phase!=Phase.READY)return false
        return try {p.keyboardEnter(session,revision)} catch(e:CancellationException) {throw e} catch(_:Exception) {false}
    }
    fun closeKeyboard(session:Long) {provider?.cancelKeyboard(session)}
    fun move(dx:Float,dy:Float) {
        val p=provider ?: return
        if(remote.value.phase!=Phase.READY || !remote.value.capabilities.pointer)return
        viewModelScope.launch { if(provider===p)runCatching { p.move(dx,dy) }.onFailure { notice.value=strings.getString(R.string.status_pointer_disconnected_reconnect_to_continue) } }
    }
    fun forget(tv:TvDevice) {
        if(selected.value?.id==tv.id) {
            holds.stop();stopMotion(); connection?.cancel(); observation?.cancel(); retry?.cancel(); provider?.disconnect(); provider=null
            while(commands.tryReceive().isSuccess) { }
            selected.value=null; shortcuts.value=emptyList(); layout.value=RemoteLayout(); canvas.value=null; remote.value=RemoteState()
        }
        if(ui.getString("last-tv",null)==tv.id)ui.edit().remove("last-tv").apply()
        vault.forget(tv); saved.value=vault.saved()
    }
    fun manual(host:String) {
        if(!LocalAddress.valid(host.trim())) { notice.value=strings.getString(R.string.status_enter_a_local_ipv4_address_such_as_192_168_1_20); return }
        viewModelScope.launch {
            notice.value=strings.getString(R.string.status_checking_device_services)
            val address=host.trim()
            val detected=withContext(Dispatchers.IO) {
                fun open(port:Int)=runCatching { Socket().use { it.connect(InetSocketAddress(address,port),1500) };true }.getOrDefault(false)
                ManualTvDetection.detect(address,open=::open,samsung={ SamsungProbe.legacyInfo(address) })
            }
            if(detected==null)notice.value=strings.getString(R.string.status_no_supported_remote_service_responded_turn_on_the_tv_and_check_ne)
            else {
                discovery.devices.update { old -> old.filterNot { it.id==detected.id }+detected }
                if(detected.platform==Platform.LG)notice.value=strings.getString(R.string.status_lg_uses_unencrypted_local_port_3000_connect_from_devices_only_on)
                else connect(detected)
            }
        }
    }
    fun bonded():List<TvDevice> = BluetoothProvider(getApplication()).bonded()
    fun pause() { foreground=false;setupProvider?.disconnect(); holds.stop();stopMotion(); while(commands.tryReceive().isSuccess) { }; retry?.cancel(); connection?.cancel(); observation?.cancel(); provider?.disconnect(); remote.value=RemoteState(Phase.DISCONNECTED,"Connection paused while OpenTV-Remote is in the background",messageRes=R.string.status_connection_paused_while_opentv_remote_is_in_the_background); discovery.stop() }
    fun resume() { foreground=true;
        setupProvider?.let { setup ->
            runCatching { setup.resumePairing() }.onFailure { bluetoothSetup.value=RemoteState(Phase.ERROR,"Could not restore phone HID. Check Nearby devices permission, then retry setup.",messageRes=R.string.status_could_not_restore_phone_hid_check_nearby_devices_permission_then) }
            // Keep the pairing sheet visible; reconnecting the previous TV would dispose its HID owner.
            return
        }
        if(selected.value!=null && remote.value.phase==Phase.DISCONNECTED)reconnect() }
    override fun onCleared() { endBluetoothSetup(); holds.stop();stopMotion(); cm.unregisterNetworkCallback(network); connection?.cancel(); observation?.cancel(); provider?.disconnect(); discovery.close(); commands.close() }
    companion object {
        fun limitation(p:Platform)=when(p) {
            Platform.ROKU -> R.string.status_roku_currently_prohibits_ecp_commands_from_third_party_mobile_pla
            Platform.SAMSUNG -> R.string.status_samsung_local_control_needs_a_compatible_tizen_websocket_service
            Platform.FIRE_TV -> R.string.status_amazon_does_not_publish_a_general_mobile_remote_control_api_adb_i
            else -> R.string.status_this_device_does_not_expose_a_supported_remote_service
        }
    }
}
