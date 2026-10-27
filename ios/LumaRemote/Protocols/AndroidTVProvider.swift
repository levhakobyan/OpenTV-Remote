import CryptoKit
import Foundation
import Security

@MainActor final class AndroidTVProvider: RemoteProvider {
  var update: ((ConnectionState) -> Void)?
  private var tv: TVDevice?
  private var socket: TLSChannel?
  private var reader: Task<Void, Never>?
  private var generation = 0
  private var identity: SecIdentity?
  private var features = 0
  private var ime = AndroidIMEState()
  private var remoteReady = false
  private var imeSending = false
  private var imeTimeout: Task<Void, Never>?
  private var imeSend: Task<Void, Never>?
  private var imeEnter: (session: Int, revision: Int)?
  private var heldKey: RemoteCommand?
  func connect(_ tv: TVDevice) async {
    disconnect()
    self.tv = tv
    let g = generation
    update?(ConnectionState(phase: .connecting, message: L10n.text("Connecting securely…")))
    do {
      identity = try ClientIdentity.load()
      if let pin = SecureStore.read("android-pin:\(tv.id)") {
        try await startRemote(tv, pin: pin, g: g)
      } else {
        let channel = try TLSChannel(host: tv.host, port: 6467, identity: identity!, pin: nil)
        socket = channel
        try await channel.start()
        try ensure(g)
        try await exchange(
          channel, 10, Wire.string(1, "atvremote") + Wire.string(2, "OpenTV-Remote"), 11)
        let encoding = Wire.integer(1, 3) + Wire.integer(2, 6)
        try await exchange(channel, 20, Wire.bytes(1, encoding) + Wire.integer(3, 1), 20)
        try await exchange(channel, 30, Wire.bytes(1, encoding) + Wire.integer(2, 1), 31)
        try ensure(g)
        update?(
          ConnectionState(
            phase: .pairing, message: L10n.text("Enter the six-character code on your TV")))
      }
    } catch {
      if g == generation {
        fail(
          L10n.text(
            "Could not connect securely. Check Wi-Fi, TV power and Android TV Remote Service. If the TV was reset, forget it and pair again."
          )
        )
      }
    }
  }
  func ensure(_ g: Int) throws {
    guard generation == g, !Task.isCancelled else { throw CancellationError() }
  }
  private func exchange(_ channel: TLSChannel, _ field: Int, _ body: Data, _ expected: Int)
    async throws
  {
    let deadline = Task {
      try? await Task.sleep(for: .seconds(8))
      if !Task.isCancelled { channel.cancel() }
    }
    defer { deadline.cancel() }
    try await channel.send(Wire.integer(1, 2) + Wire.integer(2, 200) + Wire.bytes(field, body))
    let response = try Wire.parse(await channel.receive())
    guard response.number(2) == 200, response.has(expected) else {
      throw RemoteError.message(L10n.text("TV rejected pairing"))
    }
  }
  func pair(_ code: String) async throws {
    guard code.range(of: #"^[0-9A-Fa-f]{6}$"#, options: .regularExpression) != nil,
      let channel = socket, let cert = channel.peer.get(), let identity, let tv
    else { throw RemoteError.message(L10n.text("Enter all six characters (0–9, A–F)")) }
    let g = generation
    let bytes = stride(from: 0, to: 6, by: 2).map { i in
      UInt8(String(Array(code)[i..<i + 2]), radix: 16)!
    }
    let digest = Data(
      SHA256.hash(
        data: try ClientIdentity.rsaParts(ClientIdentity.certificate(identity))
          + ClientIdentity.rsaParts(cert) + Data(bytes.dropFirst())))
    guard digest.first == bytes.first else {
      throw RemoteError.message(L10n.text("Code does not match this TV. Check it and try again."))
    }
    do {
      try await exchange(channel, 40, Wire.bytes(1, digest), 41)
      try ensure(g)
      try SecureStore.write("android-pin:\(tv.id)", SecureStore.fingerprint(cert))
      channel.cancel()
      try await startRemote(tv, pin: SecureStore.fingerprint(cert), g: g)
    } catch {
      if g == generation {
        fail(L10n.text("Pairing expired or was rejected. Reconnect to request a new code."))
      }
      throw error
    }
  }
  private func startRemote(_ tv: TVDevice, pin: String, g: Int) async throws {
    let channel = try TLSChannel(host: tv.host, port: 6466, identity: identity!, pin: pin)
    socket = channel
    try await channel.start()
    try ensure(g)
    let deadline = Task {
      try? await Task.sleep(for: .seconds(8))
      if !Task.isCancelled { channel.cancel() }
    }
    reader = Task { [weak self] in
      defer { deadline.cancel() }
      do {
        while !Task.isCancelled {
          let fields = try Wire.parse(await channel.receive())
          guard let self, g == self.generation else { return }
          if fields.has(1) {
            self.features = try fields.nested(1).number(1) & (1 | 2 | 4 | 32 | 64 | 512)
            guard self.features & 2 != 0 else {
              throw RemoteError.message(L10n.text("TV did not enable remote keys"))
            }
            let info =
              Wire.integer(3, 1) + Wire.string(4, "1") + Wire.string(5, "com.luma.remote.ios")
              + Wire.string(6, "0.9.0")
            try await channel.send(
              Wire.bytes(1, Wire.integer(1, self.features) + Wire.bytes(2, info)))
          } else if fields.has(2) {
            try await channel.send(Wire.bytes(2, Wire.integer(1, self.features)))
          } else if fields.has(8) {
            try await channel.send(Wire.bytes(9, Wire.integer(1, try fields.nested(8).number(1))))
          } else if fields.has(20) || fields.has(21) || fields.has(22), self.features & 4 != 0 {
            try self.ime.receive(fields)
            if let request = self.imeEnter,
              request.session != self.ime.keyboard.session || !self.ime.keyboard.canEdit
            {
              self.imeEnter = nil
            }
            if !self.ime.waiting { self.imeTimeout?.cancel() }
            self.publishIME()
            self.pumpIME()
          } else if fields.has(40) {
            deadline.cancel()
            self.remoteReady = true
            self.publishIME()
          }
        }
      } catch {
        guard let self, !Task.isCancelled, g == self.generation else { return }
        self.fail(
          L10n.text("TV connection lost. Reconnect to continue; commands are never replayed."))
      }
    }
  }
  func command(_ command: RemoteCommand) async throws {
    guard Capabilities.android(features).commands.contains(command), let socket else {
      throw RemoteError.message(L10n.text("Command unavailable"))
    }
    try await socket.send(Wire.key(command))
  }
  func nativeHold(_ command: RemoteCommand) -> Bool {
    command.androidNativeHold
  }
  func beginHold(_ command: RemoteCommand) async throws {
    guard nativeHold(command), features & 2 != 0, let socket else {
      throw RemoteError.message(L10n.text("Reconnect to the TV"))
    }
    heldKey = command
    try await socket.send(Wire.key(command, direction: .down))
  }
  func endHold(_ command: RemoteCommand) async throws {
    guard heldKey == command else { return }
    heldKey = nil
    if let socket { try await socket.send(Wire.key(command, direction: .up)) }
  }
  func text(_ value: String) async throws {
    try ensure(generation)
    guard features & 4 != 0, !value.isEmpty, value.utf16.count <= 1000, let socket else {
      throw RemoteError.message(L10n.text("Enter up to 1,000 characters"))
    }
    try await socket.send(ime.insertion(value))
  }
  private func publishIME() {
    guard remoteReady else { return }
    let keyboard = ime.keyboard
    update?(
      ConnectionState(
        phase: .ready, message: L10n.text("Connected · encrypted Wi-Fi"),
        capabilities: .android(features),
        keyboardRequest: keyboard.showRequest > 0 ? keyboard.showRequest : nil,
        keyboard: keyboard))
  }
  func editIME(_ value: IMETextValue, session: Int, composing: Bool) {
    guard remoteReady, ime.stage(value, session: session, composing: composing) else { return }
    if let request = imeEnter, request.revision != ime.localRevision { imeEnter = nil }
    publishIME()
    pumpIME()
  }
  private func pumpIME() {
    guard remoteReady, !imeSending, let channel = socket else { return }
    let session = ime.keyboard.session
    let bytes: Data
    let edit: Bool
    let revision = ime.localRevision
    if let replacement = ime.nextEdit(session: session) {
      bytes = replacement
      edit = true
    } else if let request = imeEnter, request.session == session,
      request.revision == revision, ime.readyToSubmit
    {
      // Enter waits behind this field's last confirmed edit; it is never replayed after a boundary.
      bytes = Wire.bytes(10, Wire.integer(1, 66) + Wire.integer(2, 3))
      edit = false
      imeEnter = nil
    } else {
      return
    }
    let g = generation
    imeSending = true
    publishIME()
    imeSend = Task { [weak self] in
      guard let self else { return }
      defer {
        if g == self.generation {
          self.imeSending = false
          self.pumpIME()
        }
      }
      do {
        try self.ensure(g)
        guard session == self.ime.keyboard.session else { return }
        if !edit, !self.ime.readyToSubmit || revision != self.ime.localRevision { return }
        try await channel.send(bytes)
        try self.ensure(g)
        guard session == self.ime.keyboard.session, self.ime.waiting else { return }
        self.imeTimeout?.cancel()
        self.imeTimeout = Task { [weak self] in
          try? await Task.sleep(for: .seconds(2))
          guard let self, !Task.isCancelled, g == self.generation else { return }
          self.ime.expire(session: session)
          if self.imeEnter?.session == session { self.imeEnter = nil }
          self.publishIME()
        }
      } catch {
        guard g == self.generation else { return }
        if self.imeEnter?.session == session { self.imeEnter = nil }
        if !edit {
          self.fail(
            L10n.text("TV connection lost. Reconnect to continue; commands are never replayed."))
          return
        }
        self.ime.expire(session: session)
        self.publishIME()
      }
    }
  }
  func submitIME(session: Int) async throws {
    guard remoteReady, features & 2 != 0, ime.keyboard.session == session,
      ime.keyboard.active, ime.keyboard.canEdit, !ime.keyboard.composing, socket != nil
    else {
      throw RemoteError.message(L10n.text("Wait for the TV to confirm the current edit."))
    }
    try ensure(generation)
    imeEnter = (session, ime.localRevision)
    pumpIME()
  }
  func stopIME() {
    imeEnter = nil
    imeTimeout?.cancel()
    imeSend?.cancel()
    ime.stopEditing()
    publishIME()
  }

  func launch(_ app: AppShortcut) async throws {
    guard features & 512 != 0, app.valid, let socket else {
      throw RemoteError.message(L10n.text("App link unavailable"))
    }
    try await socket.send(Wire.bytes(90, Wire.string(1, app.launchURI)))
  }
  private func fail(_ message: String) {
    imeEnter = nil
    remoteReady = false
    imeTimeout?.cancel()
    imeSend?.cancel()
    ime.close()
    socket?.cancel()
    update?(ConnectionState(phase: .error, message: message))
  }
  func disconnect() {
    imeEnter = nil
    generation += 1
    features = 0
    remoteReady = false
    imeTimeout?.cancel()
    imeSend?.cancel()
    imeSending = false
    ime = AndroidIMEState()
    reader?.cancel()
    reader = nil
    let old = socket
    let held = heldKey
    heldKey = nil
    socket = nil
    if let old, let held {
      Task {
        let deadline = Task {
          try? await Task.sleep(for: .milliseconds(250))
          if !Task.isCancelled { old.cancel() }
        }
        defer {
          deadline.cancel()
          old.cancel()
        }
        try? await old.send(Wire.key(held, direction: .up))
      }
    } else {
      old?.cancel()
    }
  }
}
