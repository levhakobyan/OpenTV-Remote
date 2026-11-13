package com.luma.remote.protocol

import com.luma.remote.R
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * API-level façade over the real Bluetooth HID implementation.
 *
 * The HID implementation uses APIs that exist only on Android 9+ (API 28).
 * Keeping them in a separate class that is loaded only on those versions
 * prevents class-verification failures on Android 6-8.
 */
@SuppressLint("MissingPermission", "NewApi")
class BluetoothProvider(private val context: Context, private val mouse: Boolean = true) : RemoteProvider {
    private val impl: BluetoothHidProvider? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) BluetoothHidProvider(context, mouse) else null

    private val unsupportedState = MutableStateFlow(
        RemoteState(
            Phase.ERROR,
            "Bluetooth HID requires Android 9 or newer. Use Wi-Fi instead.",
            messageRes = R.string.status_bluetooth_hid_is_unavailable_on_this_phone_use_wi_fi_instead
        )
    )

    override val state: StateFlow<RemoteState> = impl?.state ?: unsupportedState

    fun bonded(): List<TvDevice> {
        if (impl == null) return emptyList()
        return context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.bondedDevices
            ?.map { TvDevice("bt:${it.address}", it.name ?: "Paired device", it.address, Platform.BLUETOOTH) }
            ?: emptyList()
    }

    fun preparePairing() {
        val current = impl
        if (current == null) {
            unsupportedState.value = RemoteState(
                Phase.ERROR,
                "Bluetooth HID requires Android 9 or newer. Use Wi-Fi instead.",
                messageRes = R.string.status_bluetooth_hid_is_unavailable_on_this_phone_use_wi_fi_instead
            )
            return
        }
        current.preparePairing()
    }

    fun resumePairing() {
        val current = impl
        if (current == null) {
            unsupportedState.value = RemoteState(
                Phase.ERROR,
                "Bluetooth HID requires Android 9 or newer. Use Wi-Fi instead.",
                messageRes = R.string.status_bluetooth_hid_is_unavailable_on_this_phone_use_wi_fi_instead
            )
            return
        }
        current.resumePairing()
    }

    override suspend fun connect(device: TvDevice) {
        val current = impl
        if (current == null) {
            unsupportedState.value = RemoteState(
                Phase.ERROR,
                "Bluetooth HID requires Android 9 or newer. Use Wi-Fi instead.",
                messageRes = R.string.status_bluetooth_hid_is_unavailable_on_this_phone_use_wi_fi_instead
            )
            return
        }
        current.connect(device)
    }

    override suspend fun command(command: Command) {
        val current = impl ?: error("Bluetooth HID requires Android 9 or newer")
        current.command(command)
    }

    override suspend fun move(dx: Float, dy: Float) {
        val current = impl ?: error("Bluetooth HID requires Android 9 or newer")
        current.move(dx, dy)
    }

    override suspend fun scroll(amount: Float) {
        val current = impl ?: error("Bluetooth HID requires Android 9 or newer")
        current.scroll(amount)
    }

    override fun disconnect() {
        impl?.disconnect()
    }
}
