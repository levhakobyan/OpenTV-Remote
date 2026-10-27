package com.luma.remote.protocol

import com.luma.remote.R
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

@SuppressLint("MissingPermission")
class BluetoothProvider(private val context:Context,private val mouse:Boolean=true):RemoteProvider {
    override val state=MutableStateFlow(RemoteState())
    private val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid:BluetoothHidDevice?=null
    private var target:BluetoothDevice?=null
    private var registered=false
    private var generation=0
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var timeout:Job?=null
    fun bonded():List<TvDevice> = adapter?.bondedDevices?.map { TvDevice("bt:${it.address}",it.name ?: "Paired device",it.address,Platform.BLUETOOTH) } ?: emptyList()
    /** Register before discovery so a TV can see a HID accessory rather than an unclassified phone. */
    fun preparePairing() { disconnect();register() }
    /** Android can unregister HID when Settings takes foreground; the proxy alone is not registration. */
    fun resumePairing() { if(target==null && !registered)register() }
    override suspend fun connect(device:TvDevice) {
        timeout?.cancel()
        check(adapter?.isEnabled==true) { "Turn on Bluetooth in Android settings." }
        target=adapter.getRemoteDevice(device.host)
        check(target?.bondState==BluetoothDevice.BOND_BONDED) { "Pair this TV in Bluetooth settings first." }
        state.value=RemoteState(Phase.CONNECTING,"Connecting Bluetooth remote…",messageRes=R.string.status_connecting_bluetooth_remote)
        if(registered)connectTarget() else register()
        timeout=scope.launch { delay(15000);if(state.value.phase==Phase.CONNECTING)state.value=RemoteState(Phase.ERROR,"TV did not accept the HID connection. Pair from the TV, then retry. If necessary, try keyboard-only mode.",messageRes=R.string.status_tv_did_not_accept_the_hid_connection_pair_from_the_tv_then_retry) }
    }
    private fun ready() {
        timeout?.cancel()
        state.value=RemoteState(Phase.READY,if(mouse)"Connected • Bluetooth HID mouse + keyboard" else "Connected • Bluetooth keyboard",Capabilities(keys.keys,pointer=mouse,scroll=mouse),messageRes=if(mouse)R.string.status_connected_bluetooth_hid_mouse_keyboard else R.string.status_connected_bluetooth_keyboard)
    }
    private fun connectTarget() {
        val device=target ?: return
        runCatching {
            if(hid?.getConnectionState(device)==BluetoothProfile.STATE_CONNECTED)ready()
            else if(hid?.connect(device)!=true)state.value=RemoteState(Phase.ERROR,"TV declined the HID connection. Pair from its accessory settings, then reconnect. Try keyboard-only mode if mouse is unsupported.",messageRes=R.string.status_tv_declined_the_hid_connection_pair_from_its_accessory_settings_t)
        }.onFailure { state.value=RemoteState(Phase.ERROR,"Bluetooth access changed. Check Nearby devices permission and reconnect.",messageRes=R.string.status_bluetooth_access_changed_check_nearby_devices_permission_and_reco) }
    }
    private fun register() {
        val current=adapter
        if(current?.isEnabled!=true) { state.value=RemoteState(Phase.ERROR,"Turn on Bluetooth in Android settings, then retry.",messageRes=R.string.status_turn_on_bluetooth_in_android_settings_then_retry);return }
        // Replace a stale proxy too, and ignore its late unregistration callbacks.
        generation++
        val attempt=generation
        registered=false
        timeout?.cancel()
        hid?.let { previous ->
            runCatching { previous.unregisterApp() }
            runCatching { current.closeProfileProxy(BluetoothProfile.HID_DEVICE,previous) }
        }
        hid=null
        if(target==null)state.value=RemoteState(Phase.CONNECTING,"Checking phone HID support…",messageRes=R.string.status_checking_phone_hid_support)
        val accepted=current.getProfileProxy(context,object:BluetoothProfile.ServiceListener {
            override fun onServiceDisconnected(profile:Int) { if(attempt==generation) { registered=false;hid=null;state.value=RemoteState(Phase.ERROR,"Bluetooth service disconnected. Reconnect to continue.",messageRes=R.string.status_bluetooth_service_disconnected_reconnect_to_continue) } }
            override fun onServiceConnected(profile:Int,proxy:BluetoothProfile) {
                if(attempt!=generation) { current.closeProfileProxy(profile,proxy);return }
                hid=proxy as BluetoothHidDevice
                val settings=BluetoothHidDeviceAppSdpSettings("OpenTV-Remote","TV mouse and navigation keyboard","OpenTV-Remote",if(mouse)BluetoothHidDevice.SUBCLASS1_COMBO else BluetoothHidDevice.SUBCLASS1_KEYBOARD,BluetoothHidReports.descriptor(mouse))
                val acceptedRegistration=runCatching { hid!!.registerApp(settings,null,null,context.mainExecutor,object:BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(pluggedDevice:BluetoothDevice?,registered:Boolean) {
                        if(attempt!=generation)return
                        this@BluetoothProvider.registered=registered
                        if(registered) {
                            if(target!=null)connectTarget()
                            else state.value=RemoteState(Phase.PAIRING,"Phone HID ready. Make it discoverable and pair from the TV.",messageRes=R.string.status_phone_hid_ready_make_it_discoverable_and_pair_from_the_tv)
                        } else {
                            timeout?.cancel()
                            state.value=RemoteState(Phase.ERROR,"Android ended phone HID registration. Return to OpenTV-Remote and retry HID setup or reconnect the paired TV. This does not confirm a TV compatibility failure.",messageRes=R.string.status_android_ended_phone_hid_registration_return_to_opentv_remote_and_retry_hid)
                        }
                    }
                    override fun onConnectionStateChanged(device:BluetoothDevice,status:Int) {
                        if(attempt!=generation)return
                        if(target==null) { if(status==BluetoothProfile.STATE_CONNECTED)state.value=RemoteState(Phase.PAIRING,"TV paired. Refresh the list and select it to continue.",messageRes=R.string.status_tv_paired_refresh_the_list_and_select_it_to_continue);return }
                        if(device!=target)return
                        if(status==BluetoothProfile.STATE_CONNECTED)ready()
                        else if(status==BluetoothProfile.STATE_DISCONNECTED)state.value=RemoteState(Phase.ERROR,"TV disconnected Bluetooth. Keep OpenTV-Remote open, then reconnect.",messageRes=R.string.status_tv_disconnected_bluetooth_keep_opentv_remote_open_then_reconnect)
                    }
                    override fun onGetReport(device:BluetoothDevice,type:Byte,id:Byte,bufferSize:Int) {
                        if(attempt!=generation || type!=BluetoothHidDevice.REPORT_TYPE_INPUT)return
                        val neutral=when(id.toInt()) { 1 -> ByteArray(8);2 -> if(mouse)ByteArray(4) else null;else -> null }
                        if(neutral!=null)runCatching { hid?.replyReport(device,type,id,neutral) }
                    }
                }) }.getOrDefault(false)
                if(!acceptedRegistration)state.value=RemoteState(Phase.ERROR,"This phone does not allow Bluetooth HID registration. Use Wi-Fi instead.",messageRes=R.string.status_this_phone_does_not_allow_bluetooth_hid_registration_use_wi_fi_in)
            }
        },BluetoothProfile.HID_DEVICE)
        if(accepted && target==null)timeout=scope.launch {
            delay(12000)
            if(!registered && state.value.phase==Phase.CONNECTING)state.value=RemoteState(Phase.ERROR,"Phone HID service did not respond. Retry setup or use Wi-Fi.",messageRes=R.string.status_phone_hid_service_did_not_respond_retry_setup_or_use_wi_fi)
        }
        if(!accepted)state.value=RemoteState(Phase.ERROR,"Bluetooth HID is unavailable on this phone. Use Wi-Fi instead.",messageRes=R.string.status_bluetooth_hid_is_unavailable_on_this_phone_use_wi_fi_instead)
    }
    private fun report(id:Int,bytes:ByteArray) {
        check(state.value.phase==Phase.READY)
        check(hid?.sendReport(target,id,bytes)==true) { "Bluetooth send failed" }
    }
    override suspend fun command(command:Command) {
        val key=keys[command] ?: error("No keyboard mapping")
        report(1,byteArrayOf(0,0,key.toByte(),0,0,0,0,0))
        try { delay(30) } finally { hid?.sendReport(target,1,ByteArray(8)) }
    }
    override suspend fun move(dx:Float,dy:Float) { check(mouse);report(2,BluetoothHidReports.mouse(dx,dy)) }
    override suspend fun scroll(amount:Float) { check(mouse);report(2,BluetoothHidReports.mouse(wheel=amount)) }
    override suspend fun click() {
        check(mouse);report(2,BluetoothHidReports.mouse(pressed=true))
        try { delay(30) } finally { hid?.sendReport(target,2,ByteArray(4)) }
    }
    override fun disconnect() {
        generation++;timeout?.cancel()
        runCatching {
            target?.let { device -> hid?.sendReport(device,1,ByteArray(8));if(mouse)hid?.sendReport(device,2,ByteArray(4));hid?.disconnect(device) }
            hid?.unregisterApp();hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE,it) }
        }
        registered=false;hid=null;target=null;state.value=RemoteState()
    }
    companion object {
        // Keyboard behavior remains host-dependent; power/volume are not claimed.
        val keys=mapOf(Command.UP to 0x52,Command.DOWN to 0x51,Command.LEFT to 0x50,Command.RIGHT to 0x4f,Command.OK to 0x28,Command.BACK to 0x29,Command.HOME to 0x4a)
    }
}
