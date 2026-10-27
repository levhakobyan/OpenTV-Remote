import Foundation

enum SamsungCodec {
  static let keys: [RemoteCommand: String] = [
    .up: "KEY_UP", .down: "KEY_DOWN", .left: "KEY_LEFT", .right: "KEY_RIGHT", .ok: "KEY_ENTER",
    .back: "KEY_RETURN", .home: "KEY_HOME", .volumeUp: "KEY_VOLUP", .volumeDown: "KEY_VOLDOWN",
    .mute: "KEY_MUTE", .channelUp: "KEY_CHUP", .channelDown: "KEY_CHDOWN", .guide: "KEY_GUIDE",
    .info: "KEY_INFO", .menu: "KEY_MENU", .input: "KEY_SOURCE", .tv: "KEY_TV", .power: "KEY_POWER",
    .rewind: "KEY_REWIND", .forward: "KEY_FF",
  ].merging(
    Dictionary(uniqueKeysWithValues: RemoteCommand.digits.enumerated().map { ($1, "KEY_\($0)") })
  ) { a, _ in a }
  static func json(_ object: [String: Any]) throws -> String {
    String(
      decoding: try JSONSerialization.data(withJSONObject: object, options: .sortedKeys),
      as: UTF8.self)
  }
  static func key(_ command: RemoteCommand) throws -> String {
    guard let code = keys[command] else {
      throw RemoteError.message(L10n.text("Unsupported Samsung command"))
    }
    return try json([
      "method": "ms.remote.control",
      "params": [
        "Cmd": "Click", "DataOfCmd": code, "Option": "false", "TypeOfRemote": "SendRemoteKey",
      ],
    ])
  }
  static func text(_ value: String) throws -> [String] {
    guard !value.isEmpty, value.utf16.count <= 1000, !value.contains("\0") else {
      throw RemoteError.message(L10n.text("Enter up to 1,000 characters"))
    }
    return try [
      json([
        "method": "ms.channel.emit",
        "params": ["event": "custom.remote.textReceived", "to": "broadcast"],
      ]),
      json([
        "method": "ms.remote.control",
        "params": [
          "Cmd": Data(value.utf8).base64EncodedString(), "DataOfCmd": "base64",
          "TypeOfRemote": "SendInputString",
        ],
      ]),
    ]
  }
  static func validToken(_ value: String) -> Bool {
    !value.isEmpty && value.utf8.count <= 1024 && value.utf8.allSatisfy { (33...126).contains($0) }
  }
  static func info(_ raw: Data, host: String) throws -> (TVDevice, Capabilities) {
    guard raw.count <= 65536, let o = try JSONSerialization.jsonObject(with: raw) as? [String: Any],
      let d = o["device"] as? [String: Any],
      [o["type"], d["type"]].contains(where: { ($0 as? String)?.lowercased() == "samsung smarttv" })
    else { throw RemoteError.message(L10n.text("This device did not identify as a Samsung TV")) }
    var support = o["isSupport"] as? [String: Any] ?? [:]
    if let s = o["isSupport"] as? String, let data = s.data(using: .utf8) {
      support = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
    func flag(_ key: String) -> Bool? {
      let v = support[key] ?? d[key]
      guard let v else { return nil }
      switch String(describing: v).lowercased() {
      case "true", "1": return true
      case "false", "0": return false
      default: return nil
      }
    }
    guard flag("remote_available") != false else {
      throw RemoteError.message(L10n.text("This Samsung TV reports remote control unavailable"))
    }
    var commands = Set(keys.keys)
    if flag("remote_fourDirections") == false { commands.subtract(RemoteCommand.navigation) }
    let rawID = d["id"] as? String ?? o["id"] as? String ?? host
    let id =
      rawID.count <= 128 && !rawID.isEmpty
        && !rawID.contains(where: { $0.isWhitespace || $0.isNewline }) ? rawID : host
    let name = String(
      ((o["name"] as? String ?? d["name"] as? String ?? "Samsung TV").unicodeScalars.filter {
        !CharacterSet.controlCharacters.contains($0)
      }).map(String.init).joined().prefix(64))
    return (
      TVDevice(
        id: "samsung:\(id)", name: name.isEmpty ? "Samsung TV" : name, host: host,
        platform: .samsung),
      Capabilities(commands: commands, text: flag("ImeSyncedSupport") == true)
    )
  }
}
