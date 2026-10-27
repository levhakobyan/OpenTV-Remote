# Android TV Remote v2 IME research

Research date: 2026-10-27. This note distinguishes public protocol evidence from implementation policy and hardware qualification. It does not claim that OpenTV-Remote has reproduced Google TV's complete private IME implementation.

## Evidence and confidence

The current upstream [Remote v2 schema](https://raw.githubusercontent.com/tronikos/androidtvremote2/main/src/androidtvremote2/remotemessage.proto) defines these envelopes:

| RemoteMessage field | Payload | What the schema establishes |
| --- | --- | --- |
| 20 | RemoteImeKeyInject | App information and optional text-field status |
| 21 | RemoteImeBatchEdit | IME counter, field counter, repeated edit records |
| 22 | RemoteImeShowRequest | Text-field status associated with a request to show input |

Field status contains a counter, value, start/end, an unnamed integer and label. An edit record contains an unnamed integer operation (`insert`) plus start/end/value. App information includes package/label and several integers whose meanings remain undocumented. Field names alone do not prove action, password or field-identity semantics.

### Range replacement and confirmation

A richer primary [Windows sender implementation](https://raw.githubusercontent.com/falghamdi125/Google-TV-Remote-for-Windows/main/gtvremote/messages.py) reports testing on a TCL Google TV. Its operation1 replaces the half-open range `[start,end)` with the supplied value; equal endpoints insert, and an empty value deletes. The author says edits must echo current TV counters and stale counters are silently rejected. This is an upstream author's hardware claim, not independent OpenTV-Remote verification or a public Google compatibility guarantee.

The corresponding [session implementation](https://raw.githubusercontent.com/falghamdi125/Google-TV-Remote-for-Windows/main/gtvremote/remote.py) waits for a TV field-status echo before reporting success. It observes that field counters advance after accepted edits. It also warns that zero-valued batch counters occur both on focus loss and after accepted edits; zero by itself is not a reliable close signal. App changes without a text-field status invalidate its remembered field. This implementation does not establish selection-only operations, password metadata, or action metadata.

Its [protocol tests](https://github.com/falghamdi125/Google-TV-Remote-for-Windows/blob/main/tests/test_protocol.py) exercise insertion, one-character deletion, clear, successive counter updates, no-echo failure and reconnect reset against an authored mock TV. They are useful executable examples, not packet captures. Generated fixtures include an inbound operation2 record, but do not establish what operation2 means on a real TV.

The common [androidtvremote2 sender](https://raw.githubusercontent.com/tronikos/androidtvremote2/main/src/androidtvremote2/remote.py) only inserts a supplied string, using `length-1` for both positions and caching TV counters. It does not implement a synchronized document or replacement engine. Its existing insertion formula must not be copied into a live replacement path. The original [reverse-engineering notes](https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-%28aka-Android-TV%29-Remote-Control-%28v2%29) likewise describe simple insertion, not a complete edit/selection contract.

### Unicode and editor actions

Android's [InputConnection contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection) distinguishes Java-character/code-unit operations from code-point operations and warns against splitting surrogate pairs. OpenTV-Remote should use UTF-16 indices for native Android editor interoperability and test emoji/combining sequences explicitly; applying that convention to Remote v2 ranges remains a protocol interoperability assumption requiring a real-TV check. The referenced Python range example uses Python string lengths and supplies no non-BMP qualification.

Official [EditorInfo documentation](https://developer.android.com/reference/android/view/inputmethod/EditorInfo) defines separate input type, password variations, IME options, action identifiers and initial selection. It does not map those properties to Remote v2's unnamed integers. Enter key injection is not equivalent to `performEditorAction(SEARCH)` or `performEditorAction(DONE)`. No reviewed Remote v2 source establishes a general editor-action request or a selection-only opcode.

## Explicit unresolved questions

- The exact meanings of operation0/operation2 and whether a selection-only edit is available.
- Whether `RemoteAppInfo.counter` identifies an editor, an IME generation, or another value. The changing text-field counter is not a stable editor ID.
- Password/input-type/IME-option mappings for the unnamed metadata fields.
- A universally reliable field-close indication within the same foreground TV app.
- Whether every TV emits a complete document or a surrounding-text window, and how truncation is signaled.
- The complete ordering/acknowledgment contract for concurrent phone and TV edits.

No Google TV client capture or proprietary service implementation was available in this research. No third-party APK was installed, no credential was collected, and no physical TV was controlled. Unknown fields must stay unknown until captured/verified; do not rename an integer to `inputType` merely because a value looks plausible.

## Conservative implementation policy

These are application decisions rather than newly verified wire facts:

1. Decode field20,21,22 independently; an envelope can contain more than one useful field. Validate signed counters, range bounds and UTF-16 boundaries without treating omitted proto3 default scalar fields as malformed.
2. Track confirmed TV state separately from local desired text/selection. Send one range edit at a time; coalesce newer local changes while awaiting an exact compatible echo. Compute the next replacement against the last confirmed document, not against the original local draft.
3. Never overwrite newer local typing with a delayed matching echo. Ignore stale revisions. If a newer conflicting TV update cannot be safely reconciled, stop the queued edits and show explicit manual/recovery state; do not replay the draft blindly.
4. Invalidate queued edits on connection/app/editor generation changes, explicit close, dismissal and backgrounding. A changed text-field counter alone must not be interpreted as a new editor, since accepted edits also change it. Ambiguous same-app editor transitions need hardware qualification; do not pretend an unknown metadata field is a stable identity.
5. Keep phone selection available for the next range replacement. Mirror valid TV selection in the phone. Do not invent operation0 as a TV selection command without evidence.
6. Keep all editor content transient; never serialize it with layouts, profiles or pairing data, or put it in protocol logs/diagnostic exceptions. Do not infer masked text from length or replace a masked document using invented plaintext. A field with redacted, inconsistent or insufficient status must use a clearly labeled manual path. Undocumented password metadata limits must remain explicit.
7. Expose a separately labeled Enter command only when supported. Do not label it Search/Done based on a field label or guessed metadata. Never append a newline to simulate an unverified editor action.
8. Advertise live state only after the device supplies a usable baseline and compatible confirmations. Missing status, unsupported edits, counter ambiguity or no echo must not be described as synchronized. Manual Send remains explicit and user-triggered.

The protocol assumptions must be tested separately from synthetic state-machine correctness, particularly YouTube Search and TV Bro on an actual Android TV. Unit tests and a loopback mock cannot establish those apps' editor integration or OEM Remote Service behavior.
