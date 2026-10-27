import SwiftUI

struct DevicesView: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.luma) private var p
  @State private var ip = ""
  @State private var manual = false
  @State private var finding = false
  @State private var error: String?
  @State private var lgCandidate: TVDevice?
  @State private var forget: TVDevice?
  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 24) {
        HStack {
          LumaMark()
          Spacer()
          IconButton(symbol: "globe", title: L10n.text("Language")) { store.sheet = .language }
          IconButton(symbol: "paintpalette", title: L10n.text("Appearance")) {
            store.sheet = .appearance
          }
          IconButton(symbol: "info.circle", title: L10n.text("About OpenTV-Remote")) {
            store.sheet = .about
          }
        }
        VStack(alignment: .leading, spacing: 10) {
          Text(
            store.devices.isEmpty
              ? L10n.text("Your TV.\nCloser than ever.") : L10n.text("Pick up where\nyou left off.")
          ).font(.system(size: 39, weight: .semibold, design: .rounded)).tracking(-1.5)
          Text(L10n.text("One remote. Made yours.")).foregroundStyle(Color(rgb: p.muted))
        }.padding(.top, 18)
        if !store.devices.isEmpty {
          sectionLabel(L10n.text("YOUR TVs"))
          ForEach(store.devices) { tv in device(tv, saved: true) }
        }
        PrimaryButton(
          title: store.discovery.scanning
            ? L10n.text("Searching nearby…") : L10n.text("Find my TV"),
          symbol: store.discovery.scanning ? "wifi" : "sparkle.magnifyingglass"
        ) { store.discovery.start() }.disabled(store.discovery.scanning)
        if store.discovery.scanning {
          HStack(spacing: 12) {
            ProgressView()
            Text(L10n.text("Keep your TV on and use the same Wi-Fi.")).font(.subheadline)
              .foregroundStyle(
                Color(rgb: p.muted))
          }.padding(.horizontal, 4)
        }
        if !store.discovery.devices.isEmpty {
          sectionLabel(L10n.text("NEARBY"))
          ForEach(
            store.discovery.devices.filter { discovered in
              !store.devices.contains {
                $0.id == discovered.id
                  || $0.host == discovered.host && $0.platform == discovered.platform
              }
            }
          ) { tv in device(tv, saved: false) }
        }
        if let issue = store.discovery.issue {
          Text(issue).font(.footnote).foregroundStyle(Color(rgb: p.muted)).fixedSize(
            horizontal: false, vertical: true)
        }
        Button {
          manual.toggle()
        } label: {
          Label(L10n.text("Connect by IP"), systemImage: "network").frame(minHeight: 48)
        }.tint(Color(rgb: p.accent))
        if manual {
          VStack(alignment: .leading, spacing: 14) {
            Text(L10n.text("TV address")).font(.headline)
            TextField("192.168.1.20", text: $ip).keyboardType(.decimalPad)
              .textInputAutocapitalization(.never).autocorrectionDisabled().padding(14).background(
                Color(rgb: p.raised), in: RoundedRectangle(cornerRadius: 14)
              ).accessibilityIdentifier("tv-ip")
            Text(
              L10n.text(
                "Find it in your TV’s network settings. We’ll identify the TV automatically.")
            )
            .font(.footnote).foregroundStyle(Color(rgb: p.muted))
            PrimaryButton(title: finding ? L10n.text("Checking TV…") : L10n.text("Connect")) {
              Task {
                finding = true
                defer { finding = false }
                do {
                  choose(
                    try await TVDiscovery.detect(ip.trimmingCharacters(in: .whitespacesAndNewlines))
                  )
                } catch { self.error = error.localizedDescription }
              }
            }.disabled(finding)
          }.lumaCard()
        }
        Button {
          if let u = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(u)
          }
        } label: {
          Label(L10n.text("Local Network settings"), systemImage: "gearshape").font(.footnote)
            .frame(
              minHeight: 44)
        }.foregroundStyle(Color(rgb: p.muted))
      }.padding(22).frame(maxWidth: 600).frame(maxWidth: .infinity)
    }.scrollDismissesKeyboard(.interactively).background(Color(rgb: p.background)).foregroundStyle(
      Color(rgb: p.text)
    )
    .onChange(of: store.discovery.devices) { _, _ in store.refreshAddresses() }
    .alert(
      L10n.text("Couldn’t connect"),
      isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })
    ) {
      Button("OK") { error = nil }
    } message: {
      Text(error ?? "")
    }
    .confirmationDialog(
      L10n.text("LG legacy Wi-Fi"),
      isPresented: Binding(get: { lgCandidate != nil }, set: { if !$0 { lgCandidate = nil } }),
      titleVisibility: .visible
    ) {
      Button(L10n.text("Connect on trusted Wi-Fi")) {
        if let tv = lgCandidate {
          store.connect(tv)
          store.sheet = nil
        }
        lgCandidate = nil
      }
      Button(L10n.text("Cancel"), role: .cancel) { lgCandidate = nil }
    } message: {
      Text(
        L10n.text(
          "This LG connection uses unencrypted local port 3000. Commands and typed text can be visible on the network."
        )
      )
    }
    .confirmationDialog(
      L10n.text("Forget this TV?"),
      isPresented: Binding(get: { forget != nil }, set: { if !$0 { forget = nil } }),
      titleVisibility: .visible
    ) {
      Button(L10n.text("Forget TV"), role: .destructive) {
        if let tv = forget { store.forget(tv) }
        forget = nil
      }
    } message: {
      Text(L10n.text("Removes this TV’s permission, layout and shortcuts from this phone."))
    }
  }
  private func sectionLabel(_ title: String) -> some View {
    Text(title).font(.caption.weight(.semibold)).tracking(2).foregroundStyle(Color(rgb: p.muted))
  }
  private func choose(_ tv: TVDevice) {
    store.discovery.stop()
    if tv.platform == .webOS {
      lgCandidate = tv
    } else {
      store.connect(tv)
      store.sheet = nil
    }
  }
  private func device(_ tv: TVDevice, saved: Bool) -> some View {
    HStack(spacing: 14) {
      Button {
        choose(tv)
      } label: {
        HStack(spacing: 16) {
          Image(systemName: "tv").font(.title2).foregroundStyle(Color(rgb: p.accent)).frame(
            width: 50, height: 50
          ).background(Color(rgb: p.raised), in: RoundedRectangle(cornerRadius: 16))
          VStack(alignment: .leading, spacing: 4) {
            Text(tv.name).font(.headline)
            Text(tv.platform.title).font(.caption).foregroundStyle(Color(rgb: p.muted))
          }
          Spacer()
          Image(systemName: "chevron.right").font(.caption)
        }.contentShape(Rectangle())
      }.buttonStyle(.plain)
      if saved {
        Menu {
          Button(L10n.text("Forget TV"), role: .destructive) { forget = tv }
        } label: {
          Image(systemName: "ellipsis").frame(width: 44, height: 48)
        }.accessibilityLabel(L10n.format("TV options for %@", tv.name))
      }
    }.padding(14).background(Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 24))
  }
}
