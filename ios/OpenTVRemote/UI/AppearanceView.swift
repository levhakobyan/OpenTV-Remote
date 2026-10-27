import SwiftUI

struct AppearanceView: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.dismiss) private var dismiss
  @State private var hex = "D5F69A"
  @State private var custom = Color(rgb: 0xD5F69A)
  private var preview: AccentPalette { AccentPalette(hex) }
  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 24) {
          Text(L10n.text("A little more you.")).font(
            .system(size: 31, weight: .semibold, design: .rounded))
          Text(L10n.text("The accent follows your remote. Presets keep your color."))
            .foregroundStyle(
              Color(rgb: preview.muted))
          HStack {
            RemoteMark()
            Spacer()
            VStack(spacing: 10) {
              Image(systemName: "speaker.wave.3")
              Text(L10n.text("VOL")).font(.caption)
              Image(systemName: "speaker.wave.1")
            }.frame(width: 54, height: 140).background(Color(rgb: preview.surface), in: Capsule())
            ZStack {
              Circle().fill(Color(rgb: preview.surface))
              Circle().fill(Color(rgb: preview.accent)).frame(width: 65, height: 65)
              Text("OK").foregroundStyle(Color(rgb: preview.background)).font(.title3.bold())
            }.frame(width: 140, height: 140)
          }.padding(18).background(
            Color(rgb: preview.background), in: RoundedRectangle(cornerRadius: 28))
          LazyVGrid(columns: [GridItem(.adaptive(minimum: 92))], spacing: 16) {
            ForEach(AccentPalette.choices, id: \.0) { title, value in
              Button {
                hex = value
                custom = Color(rgb: UInt32(value, radix: 16)!)
              } label: {
                VStack(spacing: 9) {
                  Circle().fill(Color(rgb: UInt32(value, radix: 16)!)).frame(width: 44, height: 44)
                    .overlay {
                      if hex == value {
                        Image(systemName: "checkmark").foregroundStyle(.black).fontWeight(.bold)
                      }
                    }
                  Text(title).font(.caption)
                }.frame(maxWidth: .infinity).padding(.vertical, 8)
              }.buttonStyle(.plain).accessibilityLabel(L10n.format("%@ accent", title))
            }
          }
          ColorPicker(L10n.text("Custom color"), selection: $custom, supportsOpacity: false)
            .padding(16)
            .background(Color(rgb: preview.surface), in: RoundedRectangle(cornerRadius: 18))
            .onChange(of: custom) { _, color in
              var r: CGFloat = 0
              var g: CGFloat = 0
              var b: CGFloat = 0
              var a: CGFloat = 0
              UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
              hex = String(
                format: "%02X%02X%02X", Int((r * 255).rounded()), Int((g * 255).rounded()),
                Int((b * 255).rounded()))
            }
          HStack {
            Text("HEX").font(.caption)
            TextField("D5F69A", text: $hex).textInputAutocapitalization(.characters)
              .autocorrectionDisabled().font(.system(.body, design: .monospaced))
          }.padding(16).background(
            Color(rgb: preview.surface), in: RoundedRectangle(cornerRadius: 18))
          PrimaryButton(title: L10n.text("Use color")) {
            if let value = AccentPalette.normalize(hex) {
              store.accent = value
              dismiss()
            }
          }.disabled(AccentPalette.normalize(hex) == nil)
        }.padding(22)
      }.background(Color(rgb: preview.background)).navigationTitle(L10n.text("Appearance"))
        .navigationBarTitleDisplayMode(.inline).toolbar {
          ToolbarItem(placement: .topBarLeading) { Button(L10n.text("Cancel")) { dismiss() } }
        }
    }.environment(\.remotePalette, preview).foregroundStyle(Color(rgb: preview.text)).tint(
      Color(rgb: preview.accent)
    ).onAppear {
      hex = store.accent
      custom = Color(rgb: UInt32(store.accent, radix: 16)!)
    }
  }
}

struct LanguageView: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.remotePalette) private var p
  var body: some View {
    Panel(title: L10n.text("Language")) {
      VStack(alignment: .leading, spacing: 16) {
        Text(L10n.text("Choose the app language. Changes apply immediately."))
          .foregroundStyle(Color(rgb: p.muted))
        ForEach(AppLanguage.allCases) { language in
          Button {
            store.language = language
          } label: {
            HStack(spacing: 14) {
              Text(language.title).font(.body.weight(.medium))
              Spacer()
              if store.language == language {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color(rgb: p.accent))
              }
            }.padding(18).frame(minHeight: 58).background(
              Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 18))
          }.buttonStyle(.plain).accessibilityIdentifier("language-" + language.rawValue)
            .accessibilityAddTraits(store.language == language ? .isSelected : [])
        }
        Spacer()
      }.padding(22).foregroundStyle(Color(rgb: p.text))
    }
  }
}
