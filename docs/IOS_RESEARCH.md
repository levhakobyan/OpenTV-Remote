# iPhone technical decisions

**Current product decision (2026-10-27):** Casting has been removed from Android and iOS, including sender SDKs and temporary media servers. Casting sections below retain earlier technical research, not current product support. Native English/Russian UI, editable remotes and supported local TV transports are the current scope. The TV Companion app and its phone transports have also been removed; no separate TV receiver is supplied.


26 October 2026. Android wire references remain in [RESEARCH.md](RESEARCH.md). Native SwiftUI uses the same product/layout rules; Android platform APIs are not portable SDKs.

**V** = implemented/exercised in iPhone Simulator against a real protocol receiver; **P** = implemented/compiles, runtime or hardware qualification pending; **X** = absent or blocked for this ordinary app. Simulator is not physical-TV certification.

| Area | Route / evidence | Boundaries and primary sources |
| --- | --- | --- |
| Android TV / Google TV | **V** Bonjour/IP, RSA identity, code-proven TLS pairing, pinned Remote v2 keys/reconnect. Text/app-link codecs and explicit IME events implemented. | Remote Service v2, not an official universal remote SDK. [Protocol/schema source](https://github.com/tronikos/androidtvremote2), [Apple local TLS identity](https://developer.apple.com/documentation/network/creating-an-identity-for-local-network-tls). |
| Samsung Tizen | **V** metadata/IP classification, trust, TV denial/approval, keys/text, token reuse and changed-cert rejection. **P** SSDP and opt-in legacy flow. | 8002 first; 8001 requires consent without secure-token leakage. No generic app launch, reliable Play/Pause toggle, true pointer or offline wake. [Primary WebSocket source](https://github.com/xchwarze/samsung-tv-ws-api), [Home Assistant integration](https://www.home-assistant.io/integrations/samsungtv). |
| LG webOS | **P** paired legacy SSAP, audio/text/media and pointer-service probes. | 3000 is unencrypted and consented; 3001-only firmware unsupported. [Primary ConnectSDK implementation](https://github.com/ConnectSDK/Connect-SDK-Android-Core). |
| Discovery / permissions | **P** declared Bonjour plus bounded SSDP and local-IP fallback. | Physical-iPhone Local Network allow/deny needs hardware. Raw multicast requires Apple's restricted entitlement; ordinary signing does not grant it. [Apple TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy). |
| Bluetooth remote | **X** no equivalent ordinary-app HID-device provider implemented. | Core Bluetooth central/peripheral communication is not an Android-style HID-device API. No Bluetooth permission/setup is exposed. [Core Bluetooth](https://developer.apple.com/documentation/corebluetooth), [Apple engineer on system-managed HID](https://developer.apple.com/forums/thread/69814). |
| Google Cast (removed) | Historical **V** official 4.8.6 dynamic sender, native receiver picker and actual MiTV MP4 playback, advancing time, pause/seek/resume and disconnect from Simulator. Production local-media server was exercised on Mac against the TV. | Physical-iPhone file picking/permissions and other receivers/codecs/DRM remain unqualified. Runtime database/UI resources are required in addition to a passing build. Default Media Receiver; no OpenTV-Remote backend/API key. [iOS setup](https://developers.google.com/cast/docs/ios_sender), [integration](https://developers.google.com/cast/docs/ios_sender/integrate), [pinned package](https://github.com/googlecast/google-cast-ios-sdk/tree/4.8.6). |
| Mirroring / relay / Roku / Fire TV | **X** | No implementation; Cast media loading does not establish mirroring or remote Internet access. |

## Implementation decisions

SwiftUI/Observation targets iOS 17+. A pure Swift core owns grid, templates, palette, models and codecs for host tests. Each provider owns bounded sockets/auth; the main-actor store owns session generations, lifecycle and non-replayed commands. Keychain stores credentials/RSA; preferences store per-TV customization and separate global appearance/presets. Editor previews are drawings without a provider.

Use native keyboard, ColorPicker, haptics and accessibility actions. Text/pointer capabilities stay explicit. Directional swipes and gyro arrows remain key navigation; LG pointing requires the actual pointer socket. Physical motion tuning and sensor behavior are unverified.

ATS declares local networking without a global arbitrary-load exception. Destinations are literal private IPv4; metadata and redirects stay bound to the selected host. Samsung's first trust is explicit home-LAN TOFU, not public-CA authentication. Unknown TLS peers abort before auth traffic; changed pins fail closed. [Apple local networking configuration](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking).

Local Network requests follow actual discovery/connection. The optional multicast entitlement is a separate provisioning decision. Simulator does not implement physical-device Local Network privacy checks. [Apple privacy rules](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy).

Earlier research used the Google Cast SDK; it and its SPM lockfile have now been removed. Its resources, manifests, terms and dependency notices require distribution review. No OpenTV-Remote account, analytics or cloud relay is added; the Google SDK has its own network/service behavior. No subscription, DRM license or TV app installation is supplied. [Cast setup/dependencies](https://developers.google.com/cast/docs/ios_sender).

The app's privacy manifest declares own UserDefaults and container/user-selected-file metadata reasons. It does not replace third-party SDK privacy review or App Store disclosures. [Apple approved reason codes](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons).

Unsigned device compilation does not establish installation/signing. Physical signing and restricted multicast require the user's Apple development configuration. No team/profile/account, signed IPA, TestFlight or App Store publication was created. See [verification](TESTING.md) and [setup](../ios/README.md).

Historical Companion emulator tests do not describe current features: that receiver and both phone transports have been removed. Native TV integrations still require independent physical-device qualification.
