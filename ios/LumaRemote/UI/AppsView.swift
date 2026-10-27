import SwiftUI

struct AppsSheet: View {
  @State var profile: TVProfile
  @Environment(RemoteStore.self) private var store
  @Environment(\.dismiss) private var dismiss
  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 22) {
          LazyVGrid(columns: [GridItem(.adaptive(minimum: 130))], spacing: 12) {
            ForEach(profile.apps) { app in
              Button {
                store.launch(app)
              } label: {
                VStack(spacing: 14) {
                  ShortcutIconView(app: app)
                  Text(app.name).font(.subheadline.weight(.semibold))
                }.frame(maxWidth: .infinity).padding(22).background(
                  .white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
              }.buttonStyle(.plain)
            }
          }
          AppsConfiguration(profile: $profile)
        }.padding(20)
      }.navigationTitle(L10n.text("Apps & shortcuts")).navigationBarTitleDisplayMode(.inline)
        .toolbar {
          ToolbarItem(placement: .topBarTrailing) {
            Button(L10n.text("Done")) {
              store.saveProfile(profile)
              dismiss()
            }
          }
          ToolbarItem(placement: .topBarLeading) { Button(L10n.text("Cancel")) { dismiss() } }
        }
    }
  }
}
struct AppsConfiguration: View {
  @Binding var profile: TVProfile
  @State private var name = ""
  @State private var target = ""
  @State private var kind: ShortcutKind = .https
  @State private var icon = "play.rectangle.fill"
  @State private var advanced = false
  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      Text(L10n.text("Choose your favorites")).font(.headline)
      Text(L10n.text("Add up to eight. The TV needs a compatible app or link handler.")).font(
        .footnote
      )
      .foregroundStyle(.secondary)
      ForEach(AppShortcut.catalog) { app in
        Button {
          if profile.apps.contains(where: { $0.id == app.id }) {
            profile.apps.removeAll { $0.id == app.id }
          } else if profile.apps.count < 8 {
            profile.apps.append(app)
          }
        } label: {
          HStack(spacing: 14) {
            ShortcutIconView(app: app).frame(width: 34)
            Text(app.name)
            Spacer()
            Image(
              systemName: profile.apps.contains { $0.id == app.id }
                ? "checkmark.circle.fill" : "plus.circle")
          }.frame(minHeight: 48)
        }.buttonStyle(.plain)
      }
      ForEach(profile.apps.filter { a in !AppShortcut.catalog.contains { $0.id == a.id } }) { app in
        HStack {
          ShortcutIconView(app: app)
          Text(app.name)
          Spacer()
          Button(L10n.text("Remove"), role: .destructive) {
            profile.apps.removeAll { $0.id == app.id }
          }
        }.frame(minHeight: 48)
      }
      DisclosureGroup(L10n.text("Custom app or link"), isExpanded: $advanced) {
        VStack(spacing: 14) {
          TextField(L10n.text("Name"), text: $name).textFieldStyle(.roundedBorder)
          Picker(L10n.text("Target type"), selection: $kind) {
            Text("HTTPS").tag(ShortcutKind.https)
            Text(L10n.text("Deep link")).tag(ShortcutKind.deepLink)
            Text(L10n.text("TV app ID")).tag(ShortcutKind.package)
          }.pickerStyle(.segmented)
          TextField(kind == .package ? "org.xbmc.kodi" : "https://…", text: $target)
            .textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(
              .roundedBorder)
          Picker(L10n.text("Icon"), selection: $icon) {
            ForEach(
              ["play.rectangle.fill", "music.note", "tv", "gamecontroller", "globe"], id: \.self
            ) { Image(systemName: $0).tag($0) }
          }.pickerStyle(.segmented)
          Button(L10n.text("Add shortcut")) {
            let a = AppShortcut(name: name, target: target, symbol: icon, kind: kind)
            guard a.valid, profile.apps.count < 8, !profile.apps.contains(where: { $0.id == a.id })
            else { return }
            profile.apps.append(a)
            name = ""
            target = ""
          }.disabled(
            !AppShortcut(name: name, target: target, symbol: icon, kind: kind).valid
              || profile.apps.count >= 8)
        }.padding(.top, 14)
      }
      DisclosureGroup(L10n.text("How to find an app link")) {
        Text(
          L10n.text(
            "Start with a catalog favorite. For custom HTTPS links, use the publisher’s official TV link. For Android TV app IDs, open the TV app’s Google Play web page and copy the value after id= (for example org.xbmc.kodi). This uses the TV’s experimental market://launch handler; it may open the store instead. Deep links must come from the publisher’s documentation. They do not install an app or bypass its login. If a link opens the browser, try the correct TV package ID or publisher TV link."
          )
        ).font(.footnote).foregroundStyle(.secondary).padding(.top, 10)
      }
    }
  }
}
struct TVSetupView: View {
  @State var profile: TVProfile
  @Environment(RemoteStore.self) private var store
  @Environment(\.dismiss) private var dismiss
  var body: some View {
    NavigationStack {
      Form {
        Section {
          Text(
            L10n.text(
              "TV keys vary by TV and app. Choose the action that works with your channels; test it before saving."
            )
          ).font(.subheadline)
        }
        Section(L10n.text("TV button")) {
          Toggle(L10n.text("Hide TV button"), isOn: $profile.hideTV)
          Picker(L10n.text("Action"), selection: $profile.tvCommand) {
            ForEach(
              [RemoteCommand.tv, .input, .channelUp].filter { store.caps.commands.contains($0) }
            ) { Text($0.title).tag(Optional($0)) }
            if store.caps.apps { Text(L10n.text("Saved app")).tag(Optional<RemoteCommand>.none) }
          }
          if profile.tvCommand == nil && store.caps.apps {
            Picker(L10n.text("App"), selection: $profile.tvApp) {
              Text(L10n.text("Choose an app")).tag(Optional<String>.none)
              ForEach(profile.apps) { Text($0.name).tag(Optional($0.target)) }
            }
          }
          Button(L10n.text("Test action")) {
            if let c = profile.tvCommand {
              store.dispatch(c)
            } else if let app = profile.apps.first(where: { $0.target == profile.tvApp }) {
              store.launch(app)
            }
          }
        }
      }.scrollContentBackground(.hidden).navigationTitle(L10n.text("TV button"))
        .navigationBarTitleDisplayMode(
          .inline
        ).toolbar {
          ToolbarItem(placement: .topBarLeading) { Button(L10n.text("Cancel")) { dismiss() } }
          ToolbarItem(placement: .topBarTrailing) {
            Button(L10n.text("Done")) {
              if profile.tvCommand != nil { profile.tvApp = nil }
              store.saveProfile(profile)
              dismiss()
            }
          }
        }
    }
  }
}
struct AboutView: View {
  @Environment(RemoteStore.self) private var store
  var body: some View {
    @Bindable var store = store
    Panel(title: L10n.text("About OpenTV-Remote")) {
      Form {
        Section {
          HStack(spacing: 14) {
            LumaMark()
            VStack(alignment: .leading) {
              Text("OpenTV-Remote").font(.title2.weight(.semibold))
              Text(L10n.text("iPhone · 0.9.0 development")).foregroundStyle(.secondary)
            }
          }
          Text(L10n.text("Your phone. Your remote. Your way."))
        }
        Section(L10n.text("Language")) {
          Button {
            store.sheet = .language
          } label: {
            HStack {
              Label(L10n.text("Language"), systemImage: "globe")
              Spacer()
              Text(store.language.title).foregroundStyle(.secondary)
            }
          }.accessibilityIdentifier("open-language-settings")
        }
        Section(L10n.text("Keyboard")) {
          Toggle(L10n.text("Open on TV text focus"), isOn: $store.autoKeyboard)
          Text(
            L10n.text(
              "Only explicit Android TV focus events open the keyboard automatically. Text Send inserts text; it does not submit a TV search."
            )
          ).font(.footnote)
        }
        Section(L10n.text("Compatibility")) {
          Text(
            L10n.text(
              "Android TV Remote v2, Samsung Tizen local WebSocket and LG legacy SSAP use real local transports. TV firmware and app capabilities vary. Pairing, reconnect and commands need physical-TV qualification."
            )
          )
          Text(
            L10n.text(
              "iPhone does not offer this app an equivalent public Bluetooth Classic HID device path. Bluetooth remote is unavailable. Roku, Fire TV, screen mirroring and remote Internet control are not implemented."
            )
          )
          Text(
            L10n.text(
              "Samsung/LG SSDP needs an Apple-approved multicast entitlement on a physical iPhone. Android TV uses declared Bonjour services. Manual IP remains available."
            )
          )
        }
        Section(L10n.text("Privacy")) {
          Text(
            L10n.text(
              "No OpenTV-Remote account, analytics or cloud relay. Pairing credentials and client identity use this phone’s Keychain. Typed TV text is not saved."
            )
          )
        }
      }.scrollContentBackground(.hidden)
    }
  }
}
