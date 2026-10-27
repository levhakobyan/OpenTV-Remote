import SwiftUI

extension Color {
  init(rgb: UInt32) {
    self.init(
      .sRGB, red: Double(rgb >> 16 & 255) / 255, green: Double(rgb >> 8 & 255) / 255,
      blue: Double(rgb & 255) / 255, opacity: 1)
  }
}
private struct PaletteKey: EnvironmentKey { static let defaultValue = AccentPalette("D5F69A") }
extension EnvironmentValues {
  var remotePalette: AccentPalette {
    get { self[PaletteKey.self] }
    set { self[PaletteKey.self] = newValue }
  }
}
struct RemoteMark: View {
  @Environment(\.remotePalette) private var p
  var body: some View {
    ZStack {
      RoundedRectangle(cornerRadius: 12).fill(Color(rgb: p.accent)).frame(width: 40, height: 64)
      VStack(spacing: 8) {
        Circle().fill(Color(rgb: p.background)).frame(width: 5, height: 5)
        Circle().stroke(Color(rgb: p.background), lineWidth: 5).frame(width: 20, height: 20)
        HStack(spacing: 5) {
          Capsule().frame(width: 7, height: 4)
          Capsule().frame(width: 7, height: 4)
        }.foregroundStyle(Color(rgb: p.background))
      }
    }.frame(width: 64, height: 72).accessibilityHidden(true)
  }
}
struct IconButton: View {
  let symbol: String
  let title: String
  var prominent = false
  let action: () -> Void
  @Environment(\.remotePalette) private var p
  var body: some View {
    Button(action: action) {
      Image(systemName: symbol).font(.system(size: 22, weight: .medium)).frame(
        width: 48, height: 48
      ).foregroundStyle(Color(rgb: prominent ? p.accent : p.text)).background(
        Color(rgb: prominent ? p.raised : p.background), in: Circle())
    }.buttonStyle(.plain).accessibilityLabel(title)
  }
}
struct PrimaryButton: View {
  let title: String
  var symbol: String?
  let action: () -> Void
  @Environment(\.remotePalette) private var p
  var body: some View {
    Button(action: action) {
      HStack {
        if let symbol { Image(systemName: symbol) }
        Text(title).fontWeight(.semibold)
      }.frame(maxWidth: .infinity).frame(minHeight: 54).foregroundStyle(Color(rgb: p.background))
        .background(Color(rgb: p.accent), in: Capsule())
    }.buttonStyle(.plain)
  }
}
struct Panel<Content: View>: View {
  let title: String
  @ViewBuilder let content: () -> Content
  @Environment(\.dismiss) private var dismiss
  @Environment(\.remotePalette) private var p
  var body: some View {
    NavigationStack {
      content().background(Color(rgb: p.background)).navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline).toolbar {
          ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } }
        }.toolbarBackground(Color(rgb: p.background), for: .navigationBar).toolbarBackground(
          .visible, for: .navigationBar)
    }.tint(Color(rgb: p.accent)).preferredColorScheme(.dark)
  }
}
extension View {
  func remoteCard() -> some View {
    padding(16).background(Color(rgb: 0x1F2723), in: RoundedRectangle(cornerRadius: 24))
  }
}
