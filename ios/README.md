# OpenTV-Remote for iPhone

Native SwiftUI, iOS 17+. Current packaging version: 0.9.1 (build 13). The remote/editor, eight templates, joined controls, independent hand preference, accents, shortcuts and connection UX follow Android. Development build; physical TV/iPhone qualification remains pending. No signed IPA, TestFlight release or Internet relay is included.

## Open and run

Open **OpenTVRemote.xcodeproj**, select **OpenTVRemote**, choose an installed iPhone Simulator and Run. The app has no external package dependencies. No CocoaPods/XcodeGen or OpenTV-Remote backend credentials are needed.

For a physical iPhone, choose your own development team in Signing & Capabilities, connect the phone and Run. The checked-in signing team and bundle identifier are preserved for existing installations. An unsigned arm64 build establishes compilation, not physical installation or distribution. The Android APK cannot install on iOS.

From the workspace root:

```sh
swift test --package-path ios
xcodebuild -project ios/OpenTVRemote.xcodeproj -scheme OpenTVRemote \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
```

Verified with Xcode 27.1 / Swift 6.4 in Swift 5 language mode, iPhone 17 Pro Simulator / iOS 26.4, and an unsigned arm64 device build. See [actual checks](../docs/TESTING.md).

The local [Simulator app bundle](build/OpenTVRemote-Simulator.zip) has a [SHA-256 sidecar](build/OpenTVRemote-Simulator.zip.sha256). Unzip and drag `OpenTVRemote.app` onto a compatible Simulator. It is not an iPhone-installable IPA. The project remains the source for building/signing a device version.

## Connect

Keep phone/TV on trusted home Wi-Fi, turn on the TV and choose **Find my TV**. Brand/model entry is not required. Local Network access is triggered by real discovery/connection, not onboarding. If denied, enable it under iPhone Settings → Privacy & Security → Local Network. Simulator does not establish real-device permission behavior.

- **Android TV / Google TV:** Bonjour, TLS 6467 code pairing and pinned TLS 6466 control. Enter the TV's six hex characters. Keychain stores the per-install RSA identity and per-TV certificate pin. Negotiated features gate keys, text and app links. Android TV Remote Service must be enabled.
- **Samsung Tizen:** SSDP/metadata or IP detection; secure 8002 first. An unknown certificate stops authenticated traffic and needs explicit trusted-home-network approval, then TV permission. Tokens/pins use Keychain. Changed identities block commands; Reset Samsung pairing clears that TV's credentials and retains its profile. Optional legacy 8001 needs separate consent and never receives secure tokens. Legacy consent is implemented but not included in the iOS native acceptance record yet.
- **LG webOS:** explicit unencrypted-network consent, legacy SSAP 3000, TV approval and non-mutating service/pointer probes. Secure-only 3001 firmware is unsupported. This provider compiles but has not been runtime-qualified in iOS.


**Physical-iPhone Samsung/LG SSDP needs Apple's restricted multicast entitlement.** The optional `OpenTVRemote/Multicast.entitlements` file is not enabled in default signing. After Apple grants your App ID access, set Code Signing Entitlements to that file and use a matching profile. Without the grant, Android TV's declared Bonjour and **Connect by IP** remain available. Manual IP checks actual metadata/services; only local IPv4 literals are accepted. Simulator additionally accepts loopback for fixtures.

Saved TVs switch from the compact toolbar. Stable discovery IDs refresh saved addresses. Layout/apps are separate per TV. Backgrounding stops connections/holds/motion; foreground reconnects. Established connection loss gets one retry; security/pairing denial does not loop prompts. Commands are never replayed.

## Customize

The selected remote uses the screen without bottom navigation. Control availability follows the actual provider. Touchpad swipes send keys on Android TV; a working LG pointer socket can expose a real pointer. Motion needs physical sensors and tuning. No Android TV Wi-Fi cursor is implied.

The six-column editor supports immediate drag in Edit without prior selection, a fixed-height options toolbar, destination feedback, contextual Join/Split, corner resize, exact sizing, atomic downward collision pushing, automatic trailing height, empty-row cleanup, Undo and Done/Cancel. Visual control cards and preset previews cannot send TV commands. Presets: Classic, Live TV, Streaming, Touchpad, Minimal, Big buttons, Full remote, Numbers & touch. Save up to 12 named layouts. Hand preference and six/custom accents stay independent from presets; the native color picker and hex input preview before apply.

App-capable Android TV connections expose catalog favorites and advanced validated HTTPS/deep-link/TV-package targets with an in-app guide. The receiving TV must handle them; native launch is not guaranteed. TV-button mapping supports advertised TV/input/channel actions or a saved app. Samsung omits generic app launch, reliable combined Play/Pause, wake from offline and true pointer.

## Android TV live keyboard

With a clear, valid TV field snapshot and observed Remote v2 IME counters, the keyboard mirrors the TV text and UTF-16 selection. Native UIKit editing handles paste, replacement, deletion, autocorrection and marked-text composition. Valid provisional text is mirrored using ordinary range replacements while the phone retains its native composition. No undocumented TV composition opcode is sent. One range replacement is sent at a time; a full matching field echo confirms it before coalesced later edits are sent. Stale echoes cannot replace newer local edits. Uncorrelated changes, ambiguous field boundaries and missing acknowledgments stop queued edits and offer manual review instead of replaying them.

**Open on TV text focus** still controls automatic presentation; it does not disable manually opening the keyboard. **Enter on TV** is a separate KEYCODE_ENTER, available only for a known active field after pending edits are confirmed. Native Return commits composition and queues one field-scoped Enter behind its final acknowledgment; further typing, timeout or any field/lifecycle boundary cancels that queued action. Search/Done action metadata and a selection-only wire operation are not verified, so they are not invented: phone cursor movement applies with the next range edit.

Manual Send remains explicit for unsupported fields/transports. Its transient draft remains available for retry until Clear, dismissal, field/connection change or backgrounding; socket write success does not prove TV acceptance. Private input is the manual default and remains forced for identified protected fields. Masked readback never becomes a diff baseline. Text/drafts are not encoded into saved profiles or logged. Protocol metadata does not reliably identify every password field; no undocumented integer is treated as an input-type flag. Use private input for sensitive manual entry.

Session and connection tokens fence callbacks, cleanup, edits and Enter across field changes, TV switching, closure, inactive/background transitions and disconnects. The optional TV receiver is not used. See the protocol research and actual testing records under `docs/` for compatibility and physical-TV limits.

## Language and limits

System is selected by default, with English as the fallback. Russian uses native `en.lproj` / `ru.lproj` resources for screens, controls, accessibility labels, editor and preset descriptions, connection/pairing/security feedback and the local-network/motion permission explanations. Choose System, English or Русский from the in-app globe button or Remote menu → Language. The choice updates screens immediately and survives relaunch. System color/keyboard dialogs follow iOS language. TV names, saved preset names, custom app names, typed text and launch URLs remain unchanged.

The language picker is also available under **About OpenTV-Remote → Language** before connecting a TV. System follows the device’s preferred supported language.

Casting, Cast receiver discovery and media sharing are not included. There is no Google Cast SDK, local media server, photo/file picker or Cast service declaration.

No iOS Bluetooth remote provider is implemented: Core Bluetooth communication does not establish an ordinary-app HID-device publishing API equivalent to Android. No pretend Bluetooth remote is shown. Roku, Fire TV Wi-Fi, screen mirroring, and cloud access remain unimplemented.

## Source ownership

`Core/` owns pure models, canvas/templates, palette and codecs; `OpenTVRemote/Protocols/` owns real providers; `Data/` owns Keychain/RSA; `Discovery/` owns network classification; `State/` owns selected-session/lifecycle/persistence; `UI/` and `UI/Editor/` own native views/drafts; `Resources/` owns native English/Russian localization. `Tests/CoreTests/` runs without a simulator; `UITests/` drives actual native UI and protocol sockets.

The product, shared scheme and SwiftPM core module are `OpenTVRemote`, `OpenTVRemote` and `OpenTVRemoteCore`. The bundle ID (`com.luma.remote.ios`), Keychain aliases, saved-preference keys and isolated QA suite/launch arguments retain their existing identifiers so upgrades keep credentials and settings. Test canvas exports use `OPENTV_CANVAS_SNAPSHOT_OUTPUT`; screenshot logs use `OPENTV_QA_SCREENSHOT`.

The checked-in Xcode project includes all current sources and localized resources. Preserve user-owned signing team and project settings when adding/removing files. The obsolete project generator was removed so routine maintenance cannot overwrite signing or project settings. `swift ios/scripts/GenerateIcon.swift` regenerates the launcher from vector drawing. [Asset credits](../docs/THIRD_PARTY_ASSETS.md) cover service marks. There is no cross-phone sync or Android credential transfer.

## Reproduce native fixture tests

Use a Python environment with **pyOpenSSL**. Use test input only: the Android receiver logs wire frames. The bridge is loopback-only, starts Samsung on explicit test requests and never hosts project files/APKs. Do not run another Samsung fixture on 8001/8002 simultaneously.

```sh
# Workspace root, terminal 1:
python3 -u tools/fake_android_tv.py --ping-interval 0 > /tmp/opentv-android-tv.log 2>&1

# Terminal 2, same Python environment:
python3 -u tools/ios_qa_bridge.py --android-log /tmp/opentv-android-tv.log

# Terminal 3, use an actual installed simulator UDID:
xcodebuild -project ios/OpenTVRemote.xcodeproj -scheme OpenTVRemote \
  -destination 'platform=iOS Simulator,id=YOUR_SIMULATOR_UDID' \
  -parallel-testing-enabled NO test
```

Debug tests use a separate preferences suite and real transport sockets, not seeded READY states or simulated button responses. They preserve the ordinary installation's layout preferences. Device Keychain credentials are not copied into fixtures or shared project assets. See [research](../docs/IOS_RESEARCH.md), [parity](../docs/IOS_PARITY.md) and [screenshots](../docs/screenshots/current/ios/).

### If the app fails to open

Debug and Release retain `$(inherited) @executable_path/Frameworks` in Runpath Search Paths. Validate the built app with `python3 tools/check_ios_bundle.py /path/to/OpenTVRemote.app`. Rebuild with Xcode Run, then close and reopen from the phone icon. Do not delete the app as the first troubleshooting step; that would remove saved settings.
