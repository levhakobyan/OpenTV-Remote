import SwiftUI

struct RootView: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.scenePhase) private var scenePhase
  var body: some View {
    @Bindable var store = store
    Group { if store.selected != nil { RemoteScreen() } else { DevicesView() } }
      .id(store.language)
      .environment(\.locale, store.language.locale)
      .environment(\.remotePalette, store.palette).tint(Color(rgb: store.palette.accent))
      .preferredColorScheme(.dark)
      .sheet(item: $store.sheet) { sheet in
        SheetRouter(sheet: sheet).id(store.language).environment(store)
          .environment(\.locale, store.language.locale).environment(\.remotePalette, store.palette)
          .tint(Color(rgb: store.palette.accent))
          .presentationBackground(Color(rgb: store.palette.background)).preferredColorScheme(.dark)
      }
      .alert(
        "OpenTV-Remote",
        isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })
      ) {
        Button("OK") { store.message = nil }
      } message: {
        Text(store.message ?? "")
      }
      .task {
        store.monitorNetwork()
        store.launchLast()
      }
      .onChange(of: store.sheet != nil) { _, presented in
        if presented { store.stopHold() }
      }
      .onChange(of: scenePhase) { old, value in
        if value != .active {
          store.stopHold()
          store.cancelKeyboardForLifecycle()
        }
        if value == .background {
          store.setActive(false)
        } else if value == .active && old == .background {
          store.setActive(true)
        }
      }
  }
}
struct RemoteScreen: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.remotePalette) private var p
  @State private var code = ""
  @State private var pairing = false
  @State private var confirmLegacy = false
  @State private var reset = false
  var body: some View {
    VStack(spacing: 10) {
      HStack(spacing: 4) {
        if store.connection.phase == .ready {
          IconButton(symbol: "slider.horizontal.3", title: L10n.text("Customize remote")) {
            store.sheet = .editor
          }
        }
        Text(
          store.connection.phase == .ready
            ? L10n.text("Remote") : (store.selected?.name ?? L10n.text("TV"))
        ).font(
          .subheadline.weight(.medium)
        ).lineLimit(1).foregroundStyle(Color(rgb: p.muted))
        Spacer(minLength: 0)
        IconButton(symbol: "arrow.left.arrow.right", title: L10n.text("Switch TV")) {
          store.sheet = .devices
        }
        if store.connection.phase == .ready {
          Menu {
            Button(L10n.text("All controls"), systemImage: "circle.grid.3x3") {
              store.sheet = .allControls
            }
            if store.caps.text {
              Button(L10n.text("Keyboard"), systemImage: "keyboard") { store.sheet = .keyboard }
            }
            if store.caps.apps {
              Button(L10n.text("Apps & shortcuts"), systemImage: "play.rectangle") {
                store.sheet = .apps
              }
            }
            if store.caps.navigation || store.caps.pointer {
              Button(L10n.text("Motion remote"), systemImage: "move.3d") { store.sheet = .motion }
            }
            if store.caps.apps || !store.caps.commands.isDisjoint(with: [.tv, .input, .channelUp]) {
              Button(L10n.text("TV button"), systemImage: "tv") { store.sheet = .tvSetup }
            }
            Button(L10n.text("Language"), systemImage: "globe") { store.sheet = .language }
            Button(L10n.text("Appearance"), systemImage: "paintpalette") {
              store.sheet = .appearance
            }
            Button(L10n.text("About OpenTV-Remote"), systemImage: "info.circle") { store.sheet = .about }
            Button(L10n.text("Disconnect"), systemImage: "xmark.circle") { store.leaveRemote() }
          } label: {
            Image(systemName: "ellipsis").font(.title2).frame(width: 44, height: 48)
          }.accessibilityLabel(L10n.text("Remote menu"))
        }
        if store.caps.commands.contains(.power), store.connection.phase == .ready {
          CommandKey(command: .power).frame(width: 54, height: 54).clipShape(Circle())
        }
      }.padding(.horizontal, 16)
      if store.connection.legacy {
        HStack {
          Image(systemName: "lock.open")
          Text(L10n.text("Unencrypted legacy Wi-Fi")).font(.caption)
          Spacer()
          if store.selected?.platform == .samsung {
            Button(L10n.text("Use secure")) { store.secureSamsung() }.font(
              .caption.weight(.semibold))
          }
        }.foregroundStyle(Color(rgb: p.muted)).padding(.horizontal, 20).padding(.bottom, 6)
      }
      if store.connection.phase == .ready {
        if store.canvas.items.isEmpty {
          ContentUnavailableView {
            Label(L10n.text("Make it yours"), systemImage: "slider.horizontal.3")
          } description: {
            Text(L10n.text("Add the controls you use most."))
          } actions: {
            Button(L10n.text("Add controls")) { store.sheet = .editor }.buttonStyle(
              .borderedProminent)
          }
        } else {
          RemoteCanvasView(canvas: store.canvas).padding(.horizontal, 14)
        }
      } else {
        connectionView
      }
    }.padding(.top, 8).background(Color(rgb: p.background)).foregroundStyle(Color(rgb: p.text))
      .confirmationDialog(
        L10n.text("Try legacy Wi-Fi?"), isPresented: $confirmLegacy, titleVisibility: .visible
      ) {
        Button(L10n.text("Connect without encryption")) { store.legacySamsung() }
        Button(L10n.text("Cancel"), role: .cancel) {}
      } message: {
        Text(
          L10n.text(
            "Commands and typed text are visible on your network. Use only trusted home Wi-Fi. Secure tokens will not be sent on this connection."
          )
        )
      }
      .confirmationDialog(
        L10n.text("Reset Samsung pairing?"),
        isPresented: $reset, titleVisibility: .visible
      ) {
        Button(L10n.text("Reset pairing"), role: .destructive) {
          store.resetSamsung()
        }
      } message: {
        Text(
          L10n.text(
            "Removes this TV’s certificate and permission. Your layout and shortcuts stay saved. Confirm the new TV identity before reconnecting."
          )
        )
      }
  }
  private var connectionView: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 22) {
        if let fingerprint = store.connection.fingerprint {
          Image(systemName: "lock.shield").font(.system(size: 44, weight: .light)).foregroundStyle(
            Color(rgb: p.accent))
          Text(L10n.text("Confirm TV identity")).font(.largeTitle.weight(.semibold))
          Text(
            L10n.text(
              "Samsung TVs use local certificates. Continue for the TV you selected on a trusted home network, then allow OpenTV-Remote on the TV."
            )
          ).foregroundStyle(Color(rgb: p.muted))
          Text(L10n.text("Certificate SHA-256")).font(.caption).foregroundStyle(Color(rgb: p.muted))
          Text(fingerprint.chunked(4).joined(separator: ":")).font(
            .system(.caption, design: .monospaced)
          ).textSelection(.enabled).accessibilityIdentifier("tv-certificate-fingerprint")
          PrimaryButton(title: L10n.text("Trust TV & continue")) { store.confirmIdentity() }
        } else {
          if store.connection.phase == .connecting || store.connection.phase == .pairing {
            ProgressView().controlSize(.large).tint(Color(rgb: p.accent))
          } else {
            Image(systemName: "wifi.exclamationmark").font(.system(size: 44, weight: .light))
              .foregroundStyle(Color(rgb: p.accent))
          }
          Text(
            store.connection.phase == .pairing
              ? L10n.text("One quick connection.")
              : store.connection.phase == .error
                ? L10n.text("Let’s reconnect.") : L10n.text("Getting your TV ready.")
          ).font(.system(size: 32, weight: .semibold, design: .rounded))
          Text(store.connection.message).foregroundStyle(Color(rgb: p.muted)).fixedSize(
            horizontal: false, vertical: true)
          if store.connection.phase == .pairing
            && store.selected?.platform == .androidTV
          {
            TextField("A1B2C3", text: $code).font(
              .system(size: 30, weight: .medium, design: .monospaced)
            ).textInputAutocapitalization(.characters).autocorrectionDisabled().keyboardType(
              .asciiCapable
            ).padding(16).background(Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 18))
              .accessibilityLabel(L10n.text("TV pairing code"))
            PrimaryButton(title: pairing ? L10n.text("Pairing…") : L10n.text("Pair TV")) {
              Task {
                pairing = true
                await store.pair(code)
                pairing = false
              }
            }.disabled(pairing || code.count != 6)
          }
          if store.connection.phase == .error || store.connection.phase == .disconnected {
            PrimaryButton(title: L10n.text("Reconnect"), symbol: "arrow.clockwise") {
              store.reconnect()
            }
          }
          if store.connection.legacyOffer {
            Button(L10n.text("Try legacy Wi-Fi")) { confirmLegacy = true }.frame(minHeight: 44)
          }
          if store.connection.resetAllowed {
            Button(L10n.text("Reset Samsung pairing")) { reset = true }.frame(minHeight: 44)
          }
        }
        Button(L10n.text("Back to devices")) { store.leaveRemote() }.frame(minHeight: 48)
      }.padding(26).padding(.top, 36).frame(maxWidth: 580).frame(
        maxWidth: .infinity, alignment: .center)
    }.scrollDismissesKeyboard(.interactively)
  }
}
extension String {
  func chunked(_ count: Int) -> [String] {
    stride(from: 0, to: self.count, by: count).map { offset in
      let start = index(startIndex, offsetBy: offset)
      return String(self[start..<index(start, offsetBy: min(count, self.count - offset))])
    }
  }
}

struct SheetRouter: View {
  let sheet: RemoteSheet
  @Environment(RemoteStore.self) private var store
  var body: some View {
    switch sheet {
    case .devices: Panel(title: L10n.text("Your TVs")) { DevicesView() }
    case .editor: CanvasEditor(profile: store.profile)
    case .presets: PresetsView(profile: store.profile) { profile in store.saveProfile(profile) }
    case .appearance: AppearanceView()
    case .language: LanguageView()
    case .keyboard: KeyboardView()
    case .numbers:
      Panel(title: L10n.text("Numbers")) { NumberPad().padding(20).frame(maxHeight: 400) }
    case .allControls:
      Panel(title: L10n.text("All controls")) {
        RemoteCanvasView(
          canvas: CanvasTemplate.full.create(store.caps, apps: store.profile.apps, hand: store.hand)
        ).padding(14)
      }
    case .apps: AppsSheet(profile: store.profile)
    case .tvSetup: TVSetupView(profile: store.profile)
    case .motion: MotionView()
    case .about: AboutView()
    }
  }
}
