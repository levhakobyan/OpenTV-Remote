# iPhone product parity

Status: Android and iPhone have native English/Russian UI, the same eight preset definitions and corrected proportional/digit previews. Casting has been removed from both apps. The TV Companion and its transports have been removed. Existing Wi-Fi/Bluetooth platform limits remain below. See [actual checks](TESTING.md).

## Interface and daily use

- Discover TVs without brand/model entry; offer manual IP only as a fallback. Preserve saved TVs, quick switching, explicit pairing, permission-denied, loading, empty and recovery states.
- Use a compact connected toolbar with TV switching, secondary actions and reachable power. Keep the bottom navigation hidden during a selected TV session, including reconnects. Give the remote the available screen space.
- Match the dark coordinated surfaces, remote-shaped mark and six accent seeds: Lime `D5F69A`, Sky `9ACBFF`, Lavender `C9B5FF`, Rose `FFB2CF`, Amber `FFD18A`, Aqua `8FE4D5`. Include the saturation/brightness/hue picker, hex entry, preview, Cancel and explicit apply. Reproduce `AccentPalette` contrast behavior rather than applying unreadable raw colors.
- Keep hand preference independent of accent and presets. Volume has speaker icons; channels use +/− and CH. Use large accessible targets, readable labels, haptics and accessible alternatives to gestures.
- Match the D-pad/OK, joined volume/channel rails, Back & Home, playback, numbers, keyboard, touchpad and secondary All controls arrangement. Show only negotiated capabilities; missing commands must not become fake buttons.
- Touchpad swipes send directions on key-only connections. Pointer/click/scroll and held gyro modes require real transport capabilities. Native keyboard uses acknowledged range edits and TV readback for supported Android TV fields, with explicit Manual Send otherwise. Preserve TV-focus auto-opening, scoped Enter and the documented privacy/lifecycle limits.
- Android TV arrow buttons use native DOWN/UP lifetimes; cancellation and stale touch owners must release safely. Volume/channel/seek retain serial repetition. Repeat only hold-enabled keys. Release, cancellation, background, switching TVs or losing the connection stops delivery without replaying queued commands.

## Customization

- Preserve the six-column grid model, bounded 24 rows / 48 controls, stable item IDs and non-overlap rules. Match Android item kinds and minimum sizes, including joined groups.
- Support direct drag in iOS and Android Edit, destination highlighting, same-size swaps, selection, removal and accessible movement alternatives. Auto-grow near the bottom and trim unused trailing rows; no Add row button.
- Support corner drag resizing and exact sizing. Downward collision pushing is atomic, preserves group shapes and order, rejects overflow/above-control collisions, and agrees with its preview.
- Remove wholly empty rows after deletion, retain deliberate gaps during normal move/load, and offer explicit gap removal. Provide Undo, Done and Cancel. Editing and previewing must never send TV commands.
- Match contextual Join/Split for compatible touching controls; do not introduce a global Join action.
- Reproduce all eight visual templates: Classic, Live TV, Streaming, Touchpad, Minimal, Big buttons, Full remote, Numbers & touch. Hand choice is separate. Support named custom presets with preview, bounded save, delete confirmation and exact geometry restoration. Applying a preset must preserve the accent.
- Preserve per-TV shortcuts, icon-based catalog choices, custom validated launch targets and the in-app guide. TV-button mapping belongs to the TV profile. Only providers with real app-launch support offer shortcuts.

## Data and providers

Use explicit device, capability, connection-state and provider boundaries equivalent to Android's `protocol/Models.kt`. Native secure storage owns credentials; layouts, shortcuts, presets and appearance remain separate records. No account, telemetry or cloud relay is implied. Cross-phone syncing/export is not implemented by the folder split, and credentials must never be copied into shared project assets.

| Integration | Android reference | iPhone acceptance requirement |
| --- | --- | --- |
| Android TV / Google TV | Pinned Remote v2 pairing, key/text/app-link capability negotiation | Implement and qualify the pairing/transport lifecycle and failure states; retain honest key-only navigation semantics. |
| Samsung Tizen | Secure local WebSocket, explicit certificate trust, TV approval/token lifecycle, optional consented legacy connection | Match fail-closed identity changes, secure token handling, command/text flags and explicit downgrade consent. Hardware qualification remains necessary. |
| LG webOS | Legacy SSAP and pointer channel; secure-only firmware unsupported | Qualify supported transport and security consent independently. Do not imply broader firmware coverage. |
| Bluetooth | Android HID keyboard/mouse provider | Research public iOS APIs and TV compatibility first. Android HID behavior is not evidence that an iPhone can provide the same transport. |
| Roku / Fire TV / cloud relay | Not implemented | Remain unavailable until a real integration and acceptance evidence exist. |

## Evidence and completion gate

Match the [current screenshot references](screenshots/current/) and [UI decisions](UI_AUDIT.md); use the [verification record](TESTING.md) as behavioral acceptance cases. Android source locations below are relative to `android/app/src/main/java/com/luma/remote/`:

- `ui/remote/` and `ui/remote/editor/`: native UI, interactions and editor rendering.
- `protocol/RemoteCanvas.kt`, `CanvasPresets.kt`, `RemoteLayout.kt`, `ShortcutCodec.kt`: geometry, templates and stored-data behavior.
- `ui/theme/AccentPalette.kt`: accent normalization, surface derivation and contrast rules.
- `control/SwipeNavigator.kt`, `protocol/HoldRepeater.kt`, `MotionFilter.kt`: gesture, repeat and motion behavior.
- `protocol/Models.kt` and provider files: explicit capability contracts and actual TV transports.

Native acceptance verifies IP classification, TLS/approval, command delivery, release cancellation, Join/Split, exact and corner resizing, drag placement, accent/relaunch and Samsung text/token/changed-identity handling. Pure Swift tests cover layout invariants, all templates/hands, contrast and codec boundaries. Remaining checks include physical TVs/iPhone, Dynamic Type/VoiceOver across every panel, real LAN multicast/denial and network-change behavior, LG pointer and gyro tuning. Android evidence does not establish those iOS outcomes.

## Android TV keyboard

Both phone apps now use a transient Remote v2 editor session for range replacement and TV text/UTF-16 selection readback when a usable baseline and confirmations exist. One in-flight edit and coalesced later typing protect against duplicated text and delayed acknowledgments. Explicit field boundaries, retired contexts, connection ownership and backgrounding stop old work. Manual Send stays separate for unsupported, opaque or unconfirmed fields; no private TV text is reconstructed. Enter is a distinct key66; selection-only TV movement and Search/Done metadata are not invented. See [behavior and limits](KEYBOARD_SYNC.md), [primary research](IME_PROTOCOL_RESEARCH.md) and [actual verification](TESTING.md).
