# Remote press and hold behavior

Android and iOS distinguish a physical key lifetime from repeated short presses. These are different TV actions: holding OK can open a context menu, while repeated OK clicks may repeatedly activate the selected item.

## Current behavior

| Action | Android TV / Google TV native Wi-Fi | Other implemented connections |
| --- | --- | --- |
| Arrows | One native DOWN followed by UP on release | Repeated short presses where arrows are available |
| OK | One native DOWN followed by UP, including a brief tap | One short OK only; no repeated selections |
| Volume, channels | Repeated supported commands | Repeated supported commands |
| Rewind, fast forward | Repeated commands at a slower interval | Same, where supported |
| Delete | Repeated backspace while held | Same, only where Delete is supported |
| Power, mute, play/pause, digits, app shortcuts | One action per activation | One action per activation |
| Home, Back, Menu and other secondary commands | Short press; distinct OEM long actions are not implemented | Short press where supported |

Capability detection still decides which controls appear. The touchpad's tap-to-select is still a short tap; native OK hold applies to actual OK buttons in the D-pad, standalone controls, number pads and motion control panel.

The receiver and current TV app decide the effect of a native held key. Successful transport does not guarantee that every TV app provides an OK context menu.

## Cancellation and ordering

Release, gesture cancellation, removal of the control, backgrounding, loss of connection, switching devices and opening a panel stop active holds. Android keyboard focus loss also releases a keyboard-initiated hold. A pending native UP must finish before the next DOWN, even when an intermediate press is canceled before it starts. Releasing an old control cannot cancel the new control's hold. Unsupported-provider OK never falls back to an auto-repeat loop.

## Protocol evidence and limits

- Android remote v2's upstream [message definitions](https://raw.githubusercontent.com/tronikos/androidtvremote2/main/src/androidtvremote2/remotemessage.proto) define START_LONG / END_LONG / SHORT as 1 / 2 / 3. Its [sender](https://raw.githubusercontent.com/tronikos/androidtvremote2/main/src/androidtvremote2/remote.py) accepts a key code with that direction. This is reverse-engineered upstream evidence, not a public Google remote-control SDK guarantee.
- Samsung's upstream [LAN key implementation](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/samsungtvws/remote.py) has Press/Release packets. OpenTV-Remote currently sends Click; native Samsung hold remains a separate implementation and physical qualification task. Samsung's [remote guide](https://developer.samsung.com/smarttv/develop/guides/user-interaction/remote-control.html) describes distinct physical long-press actions, which repeated short clicks cannot replace.
- LG's [ConnectSDK mouse transport](https://raw.githubusercontent.com/ConnectSDK/Connect-SDK-Android-Core/master/src/com/connectsdk/service/webos/WebOSTVMouseSocketConnection.java) offers button/click/move/scroll without a key-down/up API in that transport.
- Android [Bluetooth HID reports](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice#sendReport(android.bluetooth.BluetoothDevice,%20int,%20byte[])) could preserve a keyboard key state until a neutral report. OpenTV-Remote currently uses short reports; TV handling of held Enter and phone HID availability need physical testing. No iPhone Bluetooth HID support is implied.

## Joystick option

A directional joystick is feasible as an optional UI mode: a center dead zone stops navigation; displacement selects one dominant direction; hysteresis prevents boundary chatter; changing direction releases the previous key first. Center OK should stay separate to prevent accidental selections. This is directional navigation, not analog gamepad input. It is not enabled by this change; classic D-pad and swipe touchpad remain available.

## Verification

The pre-fix Android release-then-repress regression failed before changing the hold scheduler. After the fix, the current Android unit suite ran 111 tests with zero failures/errors/skips after removal of the retired receiver tests. Debug APK assembly and lint passed. Regression cases cover delayed release, canceled intermediate presses, stale ownership, transport failure cleanup, safe single-shot OK fallback, and DOWN/UP key encoding.

Fifteen Android emulator UI/transport checks and the final iPhone Simulator native hold UI test passed against a loopback Android TV receiver. iOS Core tests passed24cases, and its signed Simulator build, formatter and bundle audit passed. See [TESTING.md](TESTING.md) for the executed cases and physical hardware limits.
