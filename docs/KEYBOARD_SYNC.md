# Android TV keyboard synchronization

Android and iPhone use a transient editor session for Android TV Remote v2. This is separate from manual text insertion on Android TV, Samsung or LG. It does not require a TV companion app.

## Using the keyboard

Leave **Auto keyboard** enabled in About to open the phone keyboard when the TV reports an active field. Disabling it keeps the keyboard accessible through the remote menu. A TV app that never reports its field cannot trigger automatic opening.

- **Live typing / Live keyboard:** a usable TV document and selection have arrived. Phone edits send explicit range replacements. The phone shows a waiting state until a compatible TV field update confirms the edit.
- **Manual Send:** readback or confirmation is unavailable, the field is private/opaque, or synchronization became ambiguous. TV readback, when available, is shown separately from an initially empty manual draft so it is not accidentally inserted again. The Send button inserts the user's draft; it does not synchronize the TV document or clear the draft merely because a socket write succeeded. A stopped draft requires explicit review before sending again.
- **Enter on TV:** a separate KEYCODE_ENTER event for an active supported session. Android's Enter button and the iPhone keyboard Return commit phone composition and wait for that revision to be confirmed. The iPhone toolbar Enter button stays disabled until native composition is committed; a field change, conflict, newer edit or background transition cancels the pending action. It is not a guessed Search/Done editor action and does not append a newline to the text. The TV app determines the result.

Phone cursor/selection changes determine the next replacement. Valid TV selection changes are reflected in the editor. A selection-only command to move/highlight the TV cursor has not been established and is not fabricated. Some apps, including browser-specific editors, may still need manual Send or their own on-screen input.

## Edit and lifetime rules

The model keeps confirmed TV text separate from the latest phone draft. Only one replacement is in flight; subsequent committed edits are coalesced. A matching newer TV update confirms the old edit without replacing more recent phone typing. Unrelated newer TV changes interrupt pending work rather than replaying an old replacement over them.

Offsets and selections use UTF-16, with surrogate-boundary validation. Live input is bounded to 4,096 UTF-16 units; manual insertion retains its 1,000-unit bound. Empty text, spaces, paste, selected-range replacement, autocorrection, Armenian, Russian, combining marks and emoji are covered by synthetic regression vectors. Native composition is tracked separately. Valid provisional text uses ordinary range replacements while the phone retains its marked text and selection; no undocumented TV composition opcode is sent. Committing or autocorrecting it updates the same range without duplicating text. Ambiguous repeated-character edits use the desired caret to choose a valid replacement range instead of deleting the last identical character and jumping the cursor.

Connection ownership and field-session tokens fence edits, manual sends, Enter and cleanup. Dismissal, app/background transitions, disconnects and observed context changes cancel queued work. A show request is a field boundary, not an acknowledgment of a previous field's edit. An ambiguous older show request cannot restore a stale live baseline. Zero counters alone neither close the field nor regress a known revision. Timeout/conflict becomes manual mode; edits are never blindly retried.

Android also fences deferred UI callbacks against unsolicited TV changes and orders Enter after the latest UI revision has been staged and confirmed. Cancellation does not wait on a socket write on the UI thread. iPhone callbacks carry the rendered connection owner so an old sheet cannot edit or cancel a newly selected TV.

## Privacy

Editor contents are memory-only: no saveable UI restoration, preferences, profile/layout encoding, analytics or protocol text logs. Dismantling/dismissing the editor clears local input. Known password labels and wholly or partially masked values are opaque; their TV content is not copied into a plaintext baseline or reconstructed from its length. Manual private input uses secure display and disables suggestions/autocorrection. Android's keyboard dialog also requests a secure window.

The public Remote v2 sources do not establish a reliable mapping for password/input-type/IME-action metadata. Mask/label checks are conservative safeguards, not a claim of complete input-type detection. Unknown integers remain unknown. See [protocol research and evidence limits](IME_PROTOCOL_RESEARCH.md).

## Qualification

The shared [IME edit vectors](../shared/ime-edit-fixtures.json) are authored regression data, not captures from a Google TV client. The loopback receiver in `tools/fake_android_tv.py` implements the researched range-edit contract and memory-only controls for delayed echoes, stale updates, remote edits and field changes. Its tests establish local code behavior, not TV compatibility.

Actual executed builds, state-machine and native UI checks are recorded in [TESTING.md](TESTING.md). Physical YouTube Search and TV Bro qualification must be listed separately. Full Google TV application parity is not claimed while protocol identity, editor-action and vendor-specific behavior remain unverified.
