# Final naming and compatibility audit

Audit date: 2026-10-28. The active product, native file/project/module names, UI copy, English/Russian resources, website and published screenshot gallery use OpenTV-Remote / OpenTVRemote. The iOS Xcode project and scheme are `ios/OpenTVRemote.xcodeproj` and `OpenTVRemote`.

## Deliberately preserved metadata

A literal zero-match search would break existing installations or erase historical evidence. These remaining identifiers are explicit exceptions, not display names:

| Boundary | Reason |
| --- | --- |
| Android `com.luma.remote` application ID, namespace, source packages/imports | Existing installation updates and private app data must keep the same identity. |
| iOS `com.luma.remote.ios` application ID and its test-runner ID | Preserve existing signing/project identity and installation behavior. |
| Existing Android preference, Keystore, PKCS12 and TLS aliases; iOS Keychain/protocol namespaces | Existing encrypted pairing credentials must remain readable. |
| Retired download names and removed discovery-service assertion | Cleanup and negative regression checks must still recognize obsolete artifacts. |
| Original dated logs/results in the historical verification record | Renaming their filenames in prose would fabricate evidence. |
| Branding-check implementation and its synthetic negative tests | The guard needs known obsolete samples to prove rejection. |

Disposable QA flags/suite names, diagnostic queues, lab receiver names and fake test identifiers were migrated to the new naming. No production credential or saved-layout migration was performed.

## Checks and limits

- `python3 tools/check_branding.py` rejects accidental obsolete text and filenames outside the explicit exceptions. Pages runs this guard before publication.
- Offline tests prove visible old branding is rejected while ownership-scoped compatibility/cleanup metadata is allowed.
- macOS Vision OCR checked67 prior current screenshots and the three published gallery images. Ten superseded captures were removed: obsolete branding, old typed QA labels, or removed Companion/Cast UI. Remaining captures and the three gallery images contain no detected old brand text. No screenshot pixels were edited or fabricated.
- Current Markdown links resolve. The research/parity notes now distinguish current live range editing from historical counter-only/manual behavior.
- See [executed tests and hardware limits](TESTING.md). Successful code/build/emulator checks do not establish universal real-TV compatibility.
