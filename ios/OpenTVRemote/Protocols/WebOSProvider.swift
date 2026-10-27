import Foundation

@MainActor final class WebOSProvider: RemoteProvider {
  var update: ((ConnectionState) -> Void)?
  private var session: TVSession?
  private var socket: URLSessionWebSocketTask?
  private var pointer: URLSessionWebSocketTask?
  private var reader: Task<Void, Never>?
  private var generation = 0
  private var pending = [String: CheckedContinuation<[String: Any], Error>]()
  private var caps = Capabilities()
  private var paused = false
  func connect(_ tv: TVDevice) async {
    disconnect()
    let g = generation
    update?(ConnectionState(phase: .connecting, message: L10n.text("Connecting to LG…")))
    let http = TVSession(host: tv.host)
    session = http
    let ws = http.session.webSocketTask(with: URL(string: "ws://\(tv.host):3000/")!)
    ws.maximumMessageSize = 65536
    socket = ws
    ws.resume()
    do {
      var payload: [String: Any] = [
        "pairingType": "PROMPT",
        "manifest": [
          "manifestVersion": 1,
          "permissions": [
            "CONTROL_AUDIO", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_MOUSE_AND_KEYBOARD",
            "CONTROL_INPUT_TEXT", "CONTROL_INPUT_JOYSTICK",
          ],
        ],
      ]
      if let key = SecureStore.read("lg:\(tv.id)") { payload["client-key"] = key }
      try await send(["id": "register", "type": "register", "payload": payload])
      update?(
        ConnectionState(phase: .pairing, message: L10n.text("Accept OpenTV-Remote on the TV")))
      let deadline = Task {
        try? await Task.sleep(for: .seconds(60))
        if !Task.isCancelled { ws.cancel(with: .goingAway, reason: nil) }
      }
      defer { deadline.cancel() }
      while true {
        let o = try await receive(ws)
        guard g == generation else { return }
        if o["type"] as? String == "error" {
          throw RemoteError.message(L10n.text("TV denied pairing"))
        }
        if o["type"] as? String == "registered" {
          guard let p = o["payload"] as? [String: Any], let key = p["client-key"] as? String,
            key.count <= 2048
          else { throw RemoteError.message(L10n.text("Invalid TV permission")) }
          try SecureStore.write("lg:\(tv.id)", key)
          break
        }
      }
      reader = Task { [weak self] in
        do {
          while !Task.isCancelled {
            let o = try await self?.receive(ws)
            guard let self, let o, g == self.generation else { return }
            guard let id = o["id"] as? String, let c = self.pending.removeValue(forKey: id) else {
              continue
            }
            if o["type"] as? String == "error" {
              c.resume(throwing: RemoteError.message(L10n.text("TV declined this feature")))
            } else {
              c.resume(returning: o["payload"] as? [String: Any] ?? [:])
            }
          }
        } catch { if let self, g == self.generation, !Task.isCancelled { self.fail() } }
      }
      var commands = Set<RemoteCommand>()
      if (try? await request("audio/getVolume")) != nil {
        commands.formUnion([.volumeUp, .volumeDown, .mute])
      }
      if let response = try? await request("com.webos.service.networkinput/getPointerInputSocket"),
        let path = response["socketPath"] as? String, let u = URL(string: path), u.scheme == "ws",
        u.host == tv.host, u.user == nil
      {
        let p = http.session.webSocketTask(with: u)
        p.maximumMessageSize = 65536
        p.resume()
        do {
          try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            p.sendPing { error in if let error { c.resume(throwing: error) } else { c.resume() } }
          }
          pointer = p
          commands.formUnion([.up, .down, .left, .right, .ok, .back, .home])
        } catch { p.cancel(with: .goingAway, reason: nil) }
      }
      let services = (try? await request("api/getServiceList")) ?? [:]
      let text = String(describing: services).contains("ime")
      if String(describing: services).contains("media.controls") {
        commands.formUnion([.playPause, .rewind, .forward])
      }
      guard g == generation else { return }
      caps = Capabilities(commands: commands, text: text, pointer: pointer != nil)
      update?(
        ConnectionState(
          phase: .ready, message: L10n.text("Connected · LG legacy Wi-Fi"), capabilities: caps,
          legacy: true))
    } catch { if g == generation { fail() } }
  }
  private func send(_ o: [String: Any]) async throws {
    guard let socket else { throw RemoteError.message(L10n.text("Reconnect to TV")) }
    try await socket.send(.string(try SamsungCodec.json(o)))
  }
  private func receive(_ ws: URLSessionWebSocketTask) async throws -> [String: Any] {
    let m = try await ws.receive()
    let data: Data
    switch m {
    case .data(let d): data = d
    case .string(let s): data = Data(s.utf8)
    @unknown default: throw RemoteError.message(L10n.text("Unknown TV response"))
    }
    guard data.count <= 65536,
      let o = try JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { throw RemoteError.message(L10n.text("Invalid TV response")) }
    return o
  }
  private func request(_ uri: String, _ payload: [String: Any] = [:]) async throws -> [String: Any]
  {
    let id = UUID().uuidString
    return try await withCheckedThrowingContinuation { c in
      pending[id] = c
      Task {
        do {
          try await send(["id": id, "type": "request", "uri": "ssap://\(uri)", "payload": payload])
          try await Task.sleep(for: .seconds(5))
          pending.removeValue(forKey: id)?.resume(
            throwing: RemoteError.message(L10n.text("TV request timed out")))
        } catch { pending.removeValue(forKey: id)?.resume(throwing: error) }
      }
    }
  }
  func command(_ c: RemoteCommand) async throws {
    guard caps.commands.contains(c) else { throw RemoteError.message(L10n.text("Unavailable key")) }
    if let key: [RemoteCommand: String] = [
      .up: "UP", .down: "DOWN", .left: "LEFT", .right: "RIGHT", .ok: "ENTER", .back: "BACK",
      .home: "HOME",
    ] as [RemoteCommand: String]?, let name = key[c], let pointer {
      try await pointer.send(.string("type:button\nname:\(name)\n\n"))
      return
    }
    switch c {
    case .volumeUp: _ = try await request("audio/volumeUp")
    case .volumeDown: _ = try await request("audio/volumeDown")
    case .mute:
      let current = try await request("audio/getMute")
      _ = try await request("audio/setMute", ["mute": !(current["mute"] as? Bool ?? false)])
    case .playPause:
      _ = try await request("media.controls/\(paused ? "play":"pause")")
      paused.toggle()
    case .rewind: _ = try await request("media.controls/rewind")
    case .forward: _ = try await request("media.controls/fastForward")
    default: throw RemoteError.message(L10n.text("Unavailable key"))
    }
  }
  func text(_ value: String) async throws {
    guard caps.text, !value.isEmpty, value.utf16.count <= 1000 else {
      throw RemoteError.message(L10n.text("Text unavailable or too long"))
    }
    _ = try await request("com.webos.service.ime/insertText", ["text": value, "replace": false])
  }
  func move(_ dx: Double, _ dy: Double) async throws {
    guard caps.pointer, let pointer else {
      throw RemoteError.message(L10n.text("Pointer disconnected"))
    }
    try await pointer.send(
      .string(
        "type:move\ndx:\(Int(dx.clamped(to:-127...127)))\ndy:\(Int(dy.clamped(to:-127...127)))\ndown:0\n\n"
      ))
  }
  func click() async throws {
    guard let pointer else { throw RemoteError.message(L10n.text("Pointer disconnected")) }
    try await pointer.send(.string("type:click\n\n"))
  }
  private func fail() {
    socket?.cancel(with: .goingAway, reason: nil)
    pointer?.cancel(with: .goingAway, reason: nil)
    for continuation in pending.values {
      continuation.resume(throwing: RemoteError.message(L10n.text("TV disconnected")))
    }
    pending.removeAll()
    update?(
      ConnectionState(
        phase: .error,
        message:
          L10n.text(
            "LG connection unavailable. This release supports local port 3000. Check TV approval and Wi-Fi."
          )
      ))
  }
  func disconnect() {
    generation += 1
    reader?.cancel()
    reader = nil
    socket?.cancel(with: .goingAway, reason: nil)
    pointer?.cancel(with: .goingAway, reason: nil)
    socket = nil
    pointer = nil
    session?.close()
    session = nil
    for continuation in pending.values { continuation.resume(throwing: CancellationError()) }
    pending.removeAll()
  }
}
extension Double {
  func clamped(to range: ClosedRange<Double>) -> Double {
    min(range.upperBound, max(range.lowerBound, self))
  }
}
