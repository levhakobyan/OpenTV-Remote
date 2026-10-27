import Foundation

struct AccentPalette: Equatable {
  let background: UInt32
  let surface: UInt32
  let raised: UInt32
  let accent: UInt32
  let muted: UInt32
  let text: UInt32
  let outline: UInt32
  static var choices: [(String, String)] {
    [
      (L10n.text("Lime"), "D5F69A"), (L10n.text("Sky"), "9ACBFF"),
      (L10n.text("Lavender"), "C9B5FF"),
      (L10n.text("Rose"), "FFB2CF"),
      (L10n.text("Amber"), "FFD18A"), (L10n.text("Aqua"), "8FE4D5"),
    ]
  }
  static func normalize(_ raw: String) -> String? {
    let s = raw.hasPrefix("#") ? String(raw.dropFirst()) : raw
    return s.range(of: #"^[0-9a-fA-F]{6}$"#, options: .regularExpression) != nil
      ? s.uppercased() : nil
  }
  static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
    [16, 8, 0].reduce(0) { value, shift in
      value | UInt32(
        (Double((a >> shift) & 255) * (1 - t) + Double((b >> shift) & 255) * t).rounded()) << shift
    }
  }
  static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
    func lum(_ c: UInt32) -> Double {
      zip([16, 8, 0], [0.2126, 0.7152, 0.0722]).reduce(0) { v, p in
        let x = Double(c >> p.0 & 255) / 255
        return v + (x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4)) * p.1
      }
    }
    let x = lum(a)
    let y = lum(b)
    return (max(x, y) + 0.05) / (min(x, y) + 0.05)
  }
  init(_ raw: String) {
    let seed = UInt32(Self.normalize(raw) ?? "D5F69A", radix: 16)!
    background = Self.mix(0x101214, seed, 0.025)
    surface = Self.mix(0x1C2022, seed, 0.045)
    raised = Self.mix(0x2A3033, seed, 0.065)
    var a = seed
    while Self.contrast(a, raised) < 4.5 { a = Self.mix(a, 0xFFFFFF, 0.08) }
    accent = a
    muted = Self.mix(0xBDC4C8, seed, 0.12)
    text = 0xF4F7F8
    outline = Self.mix(0x7B858C, seed, 0.14)
  }
}
