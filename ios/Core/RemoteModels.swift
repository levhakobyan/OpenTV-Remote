import Foundation

enum TVPlatform: String, Codable, CaseIterable {
  case androidTV, samsung, webOS
  var title: String {
    switch self {
    case .androidTV: "Android TV / Google TV"
    case .samsung: "Samsung Tizen"
    case .webOS: "LG webOS"
    }
  }
}
struct TVDevice: Identifiable, Codable, Equatable, Hashable {
  var id: String
  var name: String
  var host: String
  var platform: TVPlatform
}

/// Upgrade only retired entries; one unknown platform must not discard every saved TV.
enum SavedTVMigration {
  struct Result {
    var devices: [TVDevice]
    var removedIDs: Set<String>
    func isRetired(_ id: String) -> Bool { removedIDs.contains(id) || id.hasPrefix("companion:") }
    func retiredProfileIDs(_ ids: [String]) -> Set<String> { Set(ids.filter(isRetired)) }
  }
  static func isRetiredCredential(_ account: String) -> Bool {
    account.hasPrefix("companion-pin:") || account.hasPrefix("companion-token:")
  }
  static func decode(_ data: Data?) -> Result {
    guard let data, let entries = try? JSONSerialization.jsonObject(with: data) as? [Any]
    else { return Result(devices: [], removedIDs: []) }
    var devices = [TVDevice]()
    var removed = Set<String>()
    for case let entry as [String: Any] in entries {
      if entry["platform"] as? String == "companion" {
        if let id = entry["id"] as? String { removed.insert(id) }
        continue
      }
      if let raw = try? JSONSerialization.data(withJSONObject: entry),
        let tv = try? JSONDecoder().decode(TVDevice.self, from: raw)
      {
        devices.append(tv)
      }
    }
    return Result(devices: devices, removedIDs: removed)
  }
}

enum RemoteCommand: String, CaseIterable, Codable, Identifiable {
  case up, down, left, right, ok, back, home, volumeUp, volumeDown, mute, playPause, rewind,
    forward, power, settings, input, tv, apps, channelUp, channelDown, guide, info, menu, delete
  case zero, one, two, three, four, five, six, seven, eight, nine
  var id: String { rawValue }
  static let digits: [Self] = [
    .zero, .one, .two, .three, .four, .five, .six, .seven, .eight, .nine,
  ]
  static let navigation: Set<Self> = [.up, .down, .left, .right, .ok]
  var title: String {
    switch self {
    case .ok: "OK"
    case .volumeUp: L10n.text("Volume up")
    case .volumeDown: L10n.text("Volume down")
    case .playPause: L10n.text("Play / pause")
    case .forward: L10n.text("Fast forward")
    case .channelUp: L10n.text("Channel up")
    case .channelDown: L10n.text("Channel down")
    case .tv: L10n.text("Live TV")
    case .input: L10n.text("Inputs")
    case .apps: L10n.text("All apps")
    case .up: L10n.text("Up")
    case .down: L10n.text("Down")
    case .left: L10n.text("Left")
    case .right: L10n.text("Right")
    case .back: L10n.text("Back")
    case .home: L10n.text("Home")
    case .mute: L10n.text("Mute")
    case .rewind: L10n.text("Rewind")
    case .power: L10n.text("Power")
    case .settings: L10n.text("Settings")
    case .guide: L10n.text("Guide")
    case .info: L10n.text("Info")
    case .menu: L10n.text("Menu")
    case .delete: L10n.text("Delete")
    default: Self.digits.firstIndex(of: self).map(String.init) ?? rawValue
    }
  }
  var symbol: String {
    switch self {
    case .up: "chevron.up"
    case .down: "chevron.down"
    case .left: "chevron.left"
    case .right: "chevron.right"
    case .ok: "checkmark"
    case .back: "arrow.uturn.backward"
    case .home: "house"
    case .volumeUp: "speaker.wave.3"
    case .volumeDown: "speaker.wave.1"
    case .mute: "speaker.slash"
    case .playPause: "playpause.fill"
    case .rewind: "backward.fill"
    case .forward: "forward.fill"
    case .power: "power"
    case .settings: "gearshape"
    case .input: "rectangle.on.rectangle"
    case .tv: "tv"
    case .apps: "square.grid.2x2"
    case .channelUp: "plus"
    case .channelDown: "minus"
    case .guide: "list.bullet.rectangle"
    case .info: "info.circle"
    case .menu: "line.3.horizontal"
    case .delete: "delete.left"
    default: "number"
    }
  }
  var androidKey: Int {
    [
      19, 20, 21, 22, 23, 4, 3, 24, 25, 164, 85, 89, 90, 26, 176, 178, 170, 284, 166, 167, 172, 165,
      82, 67, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
    ][Self.allCases.firstIndex(of: self)!]
  }
  /// Remote v2 can preserve the physical press duration for these navigation keys.
  /// OK must never fall back to repeated short clicks on other transports.
  var androidNativeHold: Bool { Self.navigation.contains(self) }
  var repeats: Bool {
    [
      .up, .down, .left, .right, .volumeUp, .volumeDown, .channelUp, .channelDown, .rewind,
      .forward, .delete,
    ].contains(self)
  }
}
/// Once a contact leaves a key, re-entering cannot start another press until lift.
struct RemotePressContact {
  private(set) var canceled = false
  mutating func remainsInside(x: Double, y: Double, width: Double, height: Double) -> Bool {
    guard !canceled else { return false }
    guard x.isFinite, y.isFinite, width > 0, height > 0,
      x >= 0, y >= 0, x <= width, y <= height
    else {
      canceled = true
      return false
    }
    return true
  }
  mutating func reset() { canceled = false }
}

/// Serializes remote press lifetimes, including asynchronous key-up cleanup.
@MainActor final class RemoteHoldSequence {
  private var task: Task<Void, Never>?
  @discardableResult
  func start(_ operation: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
    let previous = task
    previous?.cancel()
    let next = Task { @MainActor in
      await previous?.value
      guard !Task.isCancelled else { return }
      await operation()
    }
    task = next
    return next
  }
  func stop() {
    // Do not discard this task: a new press must wait for its release to finish.
    task?.cancel()
  }
}

struct Capabilities: Equatable {
  var commands: Set<RemoteCommand> = []
  var text = false
  var pointer = false
  var apps = false
  var scroll = false
  var navigation: Bool { RemoteCommand.navigation.isSubset(of: commands) }
  static func android(_ features: Int) -> Self {
    var commands = features & 2 != 0 ? Set(RemoteCommand.allCases) : []
    if features & 64 == 0 { commands.subtract([.volumeUp, .volumeDown, .mute]) }
    if features & 32 == 0 { commands.remove(.power) }
    return Self(commands: commands, text: features & 4 != 0, apps: features & 512 != 0)
  }
}
enum ConnectionPhase { case disconnected, connecting, pairing, ready, error }
struct ConnectionState {
  var phase: ConnectionPhase = .disconnected
  var message = L10n.text("Choose a TV")
  var capabilities = Capabilities()
  var fingerprint: String?
  var legacyOffer = false
  var legacy = false
  var retryable = true
  var resetAllowed = false
  var keyboardRequest: Int?
  var keyboard: IMEKeyboardState?
}
enum ShortcutKind: String, Codable, CaseIterable { case https, deepLink, package }
struct AppShortcut: Identifiable, Codable, Equatable {
  var id: String { target }
  var name: String
  var target: String
  var symbol = "play.rectangle.fill"
  var kind: ShortcutKind = .https
  var valid: Bool {
    guard !name.trimmingCharacters(in: .whitespaces).isEmpty, name.count <= 24,
      target.count <= 2048, !target.isEmpty,
      !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
      !target.unicodeScalars.contains(where: {
        CharacterSet.whitespacesAndNewlines.union(.controlCharacters).contains($0)
      })
    else { return false }
    if kind == .package {
      return target.count <= 255
        && target.range(
          of: #"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$"#, options: .regularExpression)
          != nil
    }
    guard let u = URLComponents(string: target), u.user == nil, u.password == nil,
      let scheme = u.scheme
    else { return false }
    if kind == .https {
      return scheme == "https" && !(u.host ?? "").isEmpty && (u.port == nil || u.port == 443)
    }
    return scheme.range(of: #"^[a-z][a-z0-9+.-]{1,31}$"#, options: .regularExpression) != nil
      && ![
        "https", "http", "intent", "file", "content", "data", "javascript", "about", "blob", "tel",
        "sms", "mailto", "market",
      ].contains(scheme) && target.count > scheme.count + 1
  }
  var launchURI: String { kind == .package ? "market://launch?id=\(target)" : target }
  static let catalog: [Self] = [
    Self(name: "YouTube", target: "https://www.youtube.com/", symbol: "play.rectangle.fill"),
    Self(name: "Netflix", target: "https://www.netflix.com/", symbol: "n.square.fill"),
    Self(name: "Prime Video", target: "https://www.primevideo.com/"),
    Self(name: "Spotify", target: "https://open.spotify.com/", symbol: "music.note"),
    Self(name: "Disney+", target: "https://www.disneyplus.com/", symbol: "sparkles.tv"),
    Self(name: "Twitch", target: "https://www.twitch.tv/", symbol: "gamecontroller"),
    Self(name: "Plex", target: "https://app.plex.tv/", symbol: "film"),
  ]
}
enum LocalAddress {
  static func valid(_ host: String, allowLoopback: Bool = false) -> Bool {
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4,
      parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) && $0.count <= 3 }),
      let a = Int(parts[0]), let b = Int(parts[1]),
      parts.allSatisfy({ Int($0).map { (0...255).contains($0) } ?? false })
    else { return false }
    return a == 10 || (a == 192 && b == 168) || (a == 172 && (16...31).contains(b))
      || (a == 169 && b == 254) || (allowLoopback && a == 127)
  }
}
enum RemoteError: Error, LocalizedError {
  case message(String)
  var errorDescription: String? {
    if case .message(let value) = self { return value }
    return nil
  }
}
