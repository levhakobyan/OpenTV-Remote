import SwiftUI
import UIKit

struct KeyboardView: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.luma) private var p
  @Environment(\.scenePhase) private var scenePhase
  @State private var manual = IMETextValue(text: "", start: 0, end: 0)
  @State private var privateInput = true
  @State private var sending = false
  @State private var feedback: String?
  @State private var task: Task<Void, Never>?
  @State private var owner: Int?
  @State private var sessionOwner: Int?
  private var keyboard: IMEKeyboardState? { store.connection.keyboard }
  private var live: Bool { keyboard?.canEdit == true }
  private var value: IMETextValue { live ? keyboard?.value ?? manual : manual }
  var body: some View {
    let rendered = keyboard
    let renderedOwner = owner
    let renderedLive = rendered?.canEdit == true
    Panel(title: L10n.text("TV keyboard")) {
      ScrollView {
        VStack(alignment: .leading, spacing: 18) {
          HStack(spacing: 8) {
            Image(systemName: live ? "arrow.triangle.2.circlepath" : "paperplane")
            Text(L10n.text(live ? "Live keyboard" : "Manual Send")).font(.title2.weight(.semibold))
            Spacer()
            if keyboard?.mode == .waiting {
              ProgressView().accessibilityLabel(L10n.text("Waiting for TV…"))
            }
          }.foregroundStyle(Color(rgb: p.accent))
          Text(
            L10n.text(
              live
                ? "Text changes sync with the active TV field. Cursor movement on your phone applies to your next edit."
                : "Live sync is unavailable for this field. Send text manually; this does not synchronize the TV field."
            )
          )
          .font(.subheadline).foregroundStyle(Color(rgb: p.muted))
          if keyboard?.mode == .conflict {
            Text(
              L10n.text(
                "The TV field changed. Pending edits were stopped. Review the field before sending again."
              )
            )
            .font(.caption).foregroundStyle(Color(rgb: p.accent))
          }
          if !live, let readback = keyboard?.value, keyboard?.privateField == false {
            VStack(alignment: .leading, spacing: 4) {
              Text(L10n.text("Current TV field")).font(.caption)
              Text(readback.text).lineLimit(3)
            }.foregroundStyle(Color(rgb: p.muted))
          }
          NativeTVTextInput(
            value: value, session: keyboard?.session ?? 0, live: live,
            modelComposing: keyboard?.composing == true,
            privateInput: !live && (privateInput || keyboard?.privateField == true), palette: p,
            changed: { value, composing in
              guard let renderedOwner, renderedOwner == store.keyboardConnection,
                rendered?.session == store.connection.keyboard?.session
              else { return }
              if renderedLive, let rendered {
                store.editKeyboard(
                  value, session: rendered.session, connection: renderedOwner, composing: composing)
              } else {
                manual = value
              }
              feedback = nil
            },
            submit: {
              if renderedLive, let rendered, let renderedOwner {
                enter(session: rendered.session, connection: renderedOwner)
              }
            }
          )
          .frame(minHeight: 150, maxHeight: 220)
          .background(Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 18))
          if live {
            Button {
              if let rendered, let renderedOwner {
                enter(session: rendered.session, connection: renderedOwner)
              }
            } label: {
              Label(L10n.text("Enter on TV"), systemImage: "return").frame(
                maxWidth: .infinity, minHeight: 52)
            }.buttonStyle(.borderedProminent).disabled(keyboard?.composing == true)
            Text(
              L10n.text(
                "Enter is a separate TV key. Search or Done behavior depends on the TV app.")
            )
            .font(.caption).foregroundStyle(Color(rgb: p.muted))
          } else {
            if let review = keyboard?.reviewDraft, keyboard?.privateField == false {
              Button(L10n.text("Review unsent phone text")) {
                if renderedOwner == store.keyboardConnection,
                  rendered?.session == store.connection.keyboard?.session
                {
                  manual = review
                }
              }
              Text(
                L10n.text(
                  "Unsent edits were stopped. Review before sending to the selected TV field.")
              )
              .font(.caption).foregroundStyle(Color(rgb: p.muted))
            }
            Toggle(L10n.text("Private input"), isOn: $privateInput)
              .disabled(keyboard?.privateField == true).accessibilityIdentifier("ime-private-input")
            Text(
              L10n.text(
                "Private input hides typed text and disables keyboard suggestions. Text is never saved or logged."
              )
            )
            .font(.caption).foregroundStyle(Color(rgb: p.muted))
            PrimaryButton(
              title: sending ? L10n.text("Sending…") : L10n.text("Send text"), symbol: "arrow.up"
            ) {
              if let renderedOwner { send(session: rendered?.session, connection: renderedOwner) }
            }
            .disabled(
              sending || manual.text.isEmpty || manual.text.utf16.count > 1000 || !store.caps.text)
          }
          if let feedback {
            Text(feedback).font(.caption).foregroundStyle(Color(rgb: p.muted))
              .accessibilityIdentifier("text-send-feedback")
          }
          Button(L10n.text("Clear text")) {
            guard let renderedOwner, renderedOwner == store.keyboardConnection,
              rendered?.session == store.connection.keyboard?.session
            else { return }
            let empty = IMETextValue(text: "", start: 0, end: 0)
            if renderedLive, let rendered {
              store.editKeyboard(empty, session: rendered.session, connection: renderedOwner)
            } else {
              manual = empty
            }
            feedback = nil
          }.disabled(value.text.isEmpty || sending)
          DisclosureGroup(L10n.text("Text not appearing?")) {
            Text(
              L10n.text(
                "Select the search or text field on your TV. Some apps use their own keyboard and do not accept remote text."
              )
            )
            .font(.subheadline).foregroundStyle(Color(rgb: p.muted))
            if live {
              Button(L10n.text("Use manual Send")) {
                if let renderedOwner {
                  store.stopKeyboardSync(session: rendered?.session, connection: renderedOwner)
                }
              }
            }
          }
        }.padding(22)
      }.onAppear {
        owner = store.keyboardConnection
        sessionOwner = keyboard?.session
      }
      .onChange(of: keyboard?.session) { _, session in
        clearManual()
        sessionOwner = session
      }
      .onChange(of: store.connection.phase) { _, phase in if phase != .ready { clearManual() } }
      .onChange(of: scenePhase) { _, phase in if phase != .active { clearManual() } }
      .onDisappear {
        clearManual()
        if let owner { store.stopKeyboardSync(session: sessionOwner, connection: owner) }
      }
    }
  }
  private func clearManual() {
    task?.cancel()
    task = nil
    manual = IMETextValue(text: "", start: 0, end: 0)
    privateInput = true
    sending = false
    feedback = nil
  }
  private func send(session: Int?, connection owner: Int) {
    guard owner == store.keyboardConnection,
      !sending, !manual.text.isEmpty, manual.text.utf16.count <= 1000
    else { return }
    let text = manual.text
    sending = true
    feedback = nil
    task = Task {
      do {
        try await store.sendText(text, session: session, connection: owner)
        guard !Task.isCancelled else { return }
        feedback = L10n.text("Sent to TV. Check the selected text field.")
        UIImpactFeedbackGenerator(style: .soft).impactOccurred()
      } catch {
        if !Task.isCancelled {
          feedback = L10n.text("Text could not be sent. Check the TV field and connection.")
        }
      }
      if !Task.isCancelled { sending = false }
    }
  }
  private func enter(session: Int, connection owner: Int) {
    guard owner == store.keyboardConnection, let keyboard, session == keyboard.session,
      keyboard.canEdit, !keyboard.composing
    else { return }
    task = Task {
      do { try await store.submitKeyboard(session: keyboard.session, connection: owner) } catch {
        if !Task.isCancelled {
          feedback = L10n.text("Wait for the TV to confirm the current edit.")
        }
      }
    }
  }
}

/// UIKit supplies UTF-16 selections, autocorrection and marked-text composition events.
private struct NativeTVTextInput: UIViewRepresentable {
  let value: IMETextValue
  let session: Int
  let live: Bool
  let modelComposing: Bool
  let privateInput: Bool
  let palette: AccentPalette
  let changed: (IMETextValue, Bool) -> Void
  let submit: () -> Void
  func makeCoordinator() -> Coordinator { Coordinator(self) }
  func makeUIView(context: Context) -> RemoteIMETextView {
    let view = RemoteIMETextView()
    view.delegate = context.coordinator
    view.font = .preferredFont(forTextStyle: .body)
    view.adjustsFontForContentSizeCategory = true
    view.backgroundColor = .clear
    view.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
    view.accessibilityIdentifier = "tv-text-input"
    view.accessibilityLabel = L10n.text("Type something…")
    return view
  }
  func updateUIView(_ view: RemoteIMETextView, context: Context) {
    let coordinator = context.coordinator
    coordinator.owner = self
    let fieldChanged =
      coordinator.session != session || coordinator.live != live
      || coordinator.privateInput != privateInput
    coordinator.updating = true
    defer { coordinator.updating = false }
    if fieldChanged {
      view.unmarkText()
      coordinator.session = session
      coordinator.live = live
      coordinator.privateInput = privateInput
    }
    if view.markedTextRange != nil, !modelComposing,
      !IMETextValue.sameText(view.text ?? "", value.text)
        || view.selectedRange
          != NSRange(
            location: min(value.start, value.end), length: abs(value.end - value.start))
    {
      view.unmarkText()
    }
    guard fieldChanged || view.markedTextRange == nil else { return }
    view.textColor = UIColor(Color(rgb: palette.text))
    view.tintColor = UIColor(Color(rgb: palette.accent))
    if view.isSecureTextEntry != privateInput { view.isSecureTextEntry = privateInput }
    view.autocorrectionType = privateInput ? .no : .yes
    view.spellCheckingType = privateInput ? .no : .yes
    view.textContentType = privateInput ? .password : nil
    view.smartInsertDeleteType = privateInput ? .no : .yes
    if !IMETextValue.sameText(view.text ?? "", value.text) { view.text = value.text }
    let start = min(value.start, value.end)
    let end = max(value.start, value.end)
    let range = NSRange(location: start, length: end - start)
    if value.valid, view.selectedRange != range { view.selectedRange = range }
  }
  static func dismantleUIView(_ view: RemoteIMETextView, coordinator: Coordinator) {
    coordinator.updating = true
    view.delegate = nil
    view.text = ""
    view.resignFirstResponder()
  }
  final class Coordinator: NSObject, UITextViewDelegate {
    var owner: NativeTVTextInput
    var session = -1
    var live = false
    var privateInput = true
    var updating = false
    init(_ owner: NativeTVTextInput) { self.owner = owner }
    func textViewDidChange(_ view: UITextView) { report(view) }
    func textViewDidChangeSelection(_ view: UITextView) { report(view) }
    private func report(_ view: UITextView) {
      guard !updating else { return }
      let range = view.selectedRange
      let value = IMETextValue(text: view.text ?? "", start: range.location, end: NSMaxRange(range))
      guard value.valid else { return }
      owner.changed(value, view.markedTextRange != nil)
    }
    func textView(
      _ view: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String
    ) -> Bool {
      if text == "\n", owner.live {
        if view.markedTextRange != nil {
          view.unmarkText()
        }
        report(view)
        owner.submit()
        return false
      }
      return true
    }
  }
}

private final class RemoteIMETextView: UITextView {
  private var focusedOnce = false
  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window != nil, !focusedOnce {
      focusedOnce = true
      DispatchQueue.main.async { [weak self] in self?.becomeFirstResponder() }
    }
  }
}
