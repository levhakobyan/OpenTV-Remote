# OpenTV-Remote architecture

The workspace contains native Android (`android/`, Gradle `:app`) and iPhone (`ios/`, Xcode `OpenTVRemote`) applications. The TV Companion module and its transports have been removed; no cloud relay is implemented. Shared research, product behavior, UI references and acceptance records live under `docs/`; local protocol fixtures live under `tools/`. See [iPhone parity](IOS_PARITY.md).

The following source paths are relative to `android/app/src/main/java/com/luma/remote/`:

- `ui/OpenTVApp.kt`: app shell, theme, onboarding, device management and language selection.
- `ui/remote/RemoteScreen.kt`: connected-remote composition and secondary panels.
- `ui/remote/RemoteTouchpad.kt`: touch rendering and gesture lifetime; `MotionRemoteDialog.kt` and `TvKeyboardDialog.kt` own those separate modes.
- `ui/remote/controls/`: reusable command buttons, hold handling, D-pad and joined volume/channel/playback/navigation controls. These components do not depend on the layout editor.
- `ui/remote/editor/`: canvas editing, palette, preview/placement, visual picker and TV-action configuration. Legacy block data/rendering are retained for existing saved layouts; the duplicate editor is retired.
- `control/SwipeNavigator.kt`: pure per-contact swipe interpretation, without Android UI or transport calls.
- `protocol/`: provider interface, capability and command models, Android TV / Samsung / LG / Bluetooth transports, discovery and existing stored layout codecs.
- `data/Vault.kt`: saved TVs, per-TV layouts/shortcuts and encrypted pairing credentials.
- `RemoteViewModel.kt`: connection ownership, lifecycle/reconnect, capability gating and serialized normal command delivery; held and motion commands do not build a backlog.

iOS uses SwiftUI, Network/Security and Keychain. Compose and Android transport APIs are not cross-platform components. Platform providers require separate qualification; no cloud wire protocol, casting sender or cross-phone credential sync is supplied.

## iPhone ownership

- `ios/Core/`: pure Swift commands/capabilities, grid/templates, palette, shortcuts and bounded wire/Samsung/range codecs. The same source files compile into the app and host-test package.
- `ios/OpenTVRemote/State/RemoteStore.swift`: selected session/generation fencing, foreground/network recovery, hold cancellation and preferences. Pairing/security denial does not automatically loop; established connection loss gets one automatic retry.
- `Data/SecureStore.swift`: Keychain credentials and per-install RSA identity, canonical X.509 and public-key extraction. Code proves the first Android TV pairing; later sessions pin the certificate.
- `Protocols/`: independent Android TV, Samsung and LG transports. Samsung session/task trust handlers build the chain and require explicit leaf trust/pinning. Unknown peers abort before auth; changed pins fail closed, and legacy mode requires consent without sending secure tokens.
- `UI/` and `UI/Editor/`: native controls, staged appearance/shortcuts, draft geometry/Undo and read-only previews. Accent and hand preference remain separate from presets.
- `Discovery/`: declared Bonjour, bounded SSDP and IP classification. Restricted physical-iPhone multicast provisioning is separate from Local Network permission.

Xcode generation is deterministic under `ios/scripts/`. See [iOS setup](../ios/README.md) for signing and native tests.

## Android compatibility boundaries

0.5 adds persisted canvas item kinds for joined groups without rewriting saved 0.4 canvases. Opening an existing layout preserves it. The contextual selected-item **Join** editor action combines only adjacent matching keys and preserves the group rectangle and surrounding controls. New presets already use groups. Splitting a group creates individual supported keys within its rectangle. Older APKs do not understand the new kinds; downgrading an APK is not a supported layout migration.


0.5.1 derives saved canvas height from the bottommost item. Placement/addition may grow up to the existing 24-row bound; compacted() removes only unused trailing height. Vault normalizes loaded/saved layouts, and the editor applies the same operation to edits and Undo snapshots. The board's temporary drag/drop space is presentation state, not persisted rows. No item coordinates are shifted by trimming.


Current storage cleanup is owned by LayoutStorageCleanup and Vault. Valid canvases normalize fitting/height on startup; retired block-editor fields are removed only when a canvas is active. Legacy layouts that still render retain their block data, with fitting upgraded. TV-action fields, custom canvas items, pairing credentials and unknown future fields are preserved. Cleanup also runs on subsequent saves, preventing retired fields from being reintroduced. Superseded UI screenshots/audits are removed; keep only the current interface evidence in screenshots/current. Do not treat active user customization or credential data as garbage.

Named user presets live in the vault under one global `canvas-presets` key, independent of per-TV layout/pairing records. `CanvasPresets.kt` owns template construction, bounded preset serialization and capability filtering. Preset app targets are validated and carried alongside geometry; applying a preset stages metadata until editor Done. The shared `ShortcutEditor` has no transport reference. `AllControlsRemote` reuses existing command/hold controls instead of duplicating dispatch.

Bluetooth keyboard and mouse use separate report IDs through `BluetoothHidReports`. Setup registers a temporary provider before TV pairing; selecting a bonded TV transfers its ownership to the normal ViewModel connection. Callback generations ignore obsolete registrations. The selected transport owns motion/click/scroll; capabilities gate each operation. Wi-Fi and Bluetooth are selected independently; no hidden cross-transport synchronization exists.

Appearance is global UI state (`accent-color`), independent from per-TV canvas/layout records and global `canvas-presets`. Hand preference is stored separately under `hand-preference`; it influences template construction and All controls rocker placement, never silently transforms saved geometry. `AccentPalette` owns pure RGB validation/tone/contrast math; `OpenTVTheme` supplies MaterialTheme colors to existing UI. Drawing lambdas capture current composed colors. `AppearanceDialog` stages its selection locally until Use color. Preset previews draw the same model passed to Apply without constructing live controls.

RemoteCanvas.removeEmptyRows is an explicit compaction operation used after deletion, for the contextual gap-removal action and by template construction. Normal loading/moving still uses trailing-only compacted(), preserving existing positions and snap targets. joinMatchingButtons accepts a selected ID; the editor exposes only contextual joining, and unrelated groups remain unchanged.

SamsungProtocol owns bounded metadata parsing, key mapping and encoded text. SamsungProvider owns approval/reconnect, certificate pin selection, bounded WebSocket traffic, stale-callback generations and encrypted credential keys via SamsungSecrets/Vault. SamsungTrust aborts unknown peers before auth and checks a per-device pin on subsequent connections. SamsungProbe reads only bounded same-host metadata without redirects. Discovery and manual IP share device classification; Samsung never routes through BluetoothProvider. Auth/protocol errors are marked non-retryable, while established transport losses retain the ViewModel reconnect policy. Pairing reset removes only the selected Samsung credentials. UI trust/legacy confirmations do not clear the user's layouts.
