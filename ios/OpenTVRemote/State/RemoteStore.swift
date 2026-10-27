import Network
import Observation
import SwiftUI

struct TVProfile: Codable {
  var canvas: RemoteCanvas?
  var apps = Array(AppShortcut.catalog.prefix(2))
  var tvCommand: RemoteCommand? = .tv
  var tvApp: String?
  var hideTV = false
}
@MainActor @Observable final class RemoteStore {
  var devices: [TVDevice]
  var selected: TVDevice?
  var connection = ConnectionState()
  var profiles: [String: TVProfile]
  var presets: [SavedPreset]
  var accent: String { didSet { defaults.set(accent, forKey: "accent") } }
  var hand: HandPreference { didSet { defaults.set(hand.rawValue, forKey: "hand") } }
  var autoKeyboard: Bool { didSet { defaults.set(autoKeyboard, forKey: "auto-keyboard") } }
  var language: AppLanguage {
    didSet {
      language.save(to: defaults)
      L10n.language = language
    }
  }
  var message: String?
  var sheet: RemoteSheet?
  let discovery = TVDiscovery()
  private let defaults: UserDefaults
  private var provider: RemoteProvider?
  private var connectTask: Task<Void, Never>?
  private let holdSequence = RemoteHoldSequence()
  private var generation = 0
  var keyboardConnection: Int { generation }
  private var sending = false
  private var reconnectUsed = false
  private var foreground = true
  private var lastKeyboard: Int?
  private var path: NWPathMonitor?
  private var networkSignature: String?
  var caps: Capabilities { connection.capabilities }
  var palette: AccentPalette { AccentPalette(accent) }
  var profile: TVProfile { selected.flatMap { profiles[$0.id] } ?? TVProfile() }
  var canvas: RemoteCanvas {
    profile.canvas ?? CanvasTemplate.classic.create(caps, apps: profile.apps, hand: hand)
  }
  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
    func read<T: Decodable>(_ key: String, _ fallback: T) -> T {
      defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) } ?? fallback
    }
    let migration = SavedTVMigration.decode(defaults.data(forKey: "devices"))
    devices = migration.devices
    profiles = read("profiles", [:])
    presets = read("presets", [])
    let appLanguage = AppLanguage.load(from: defaults)
    language = appLanguage
    L10n.language = appLanguage
    accent = AccentPalette.normalize(defaults.string(forKey: "accent") ?? "") ?? "D5F69A"
    hand = HandPreference(rawValue: defaults.string(forKey: "hand") ?? "right") ?? .right
    autoKeyboard = defaults.object(forKey: "auto-keyboard") as? Bool ?? true
    let retired = migration.removedIDs.union(migration.retiredProfileIDs(Array(profiles.keys)))
    for id in retired { profiles.removeValue(forKey: id) }
    if let last = defaults.string(forKey: "last-tv"),
      migration.isRetired(last)
    {
      defaults.removeObject(forKey: "last-tv")
    }
    SecureStore.removeRetiredReceiverCredentials()
    if !retired.isEmpty { persist() }
  }
  func persist() {
    defaults.set(try? JSONEncoder().encode(devices), forKey: "devices")
    defaults.set(try? JSONEncoder().encode(profiles), forKey: "profiles")
    defaults.set(try? JSONEncoder().encode(presets), forKey: "presets")
  }
  func saveProfile(_ profile: TVProfile) {
    guard let selected else { return }
    guard profile.canvas?.valid != false, profile.apps.count <= 8, profile.apps.allSatisfy(\.valid)
    else { return }
    profiles[selected.id] = profile
    persist()
  }
  func launchLast() {
    guard selected == nil, let id = defaults.string(forKey: "last-tv"),
      let tv = devices.first(where: { $0.id == id })
    else { return }
    connect(tv)
  }
  func connect(_ candidate: TVDevice, automatic: Bool = false) {
    if sheet == .keyboard { sheet = nil }
    var tv = candidate
    if let saved = devices.first(where: {
      $0.id == candidate.id || ($0.host == candidate.host && $0.platform == candidate.platform)
    }) {
      tv.id = saved.id
    }
    generation += 1
    let g = generation
    connectTask?.cancel()
    stopHold()
    provider?.disconnect()
    selected = tv
    lastKeyboard = nil
    sending = false
    if !automatic { reconnectUsed = false }
    defaults.set(tv.id, forKey: "last-tv")
    connection = ConnectionState(
      phase: .connecting, message: L10n.format("Connecting to %@…", tv.name))
    let service: RemoteProvider
    switch tv.platform {
    case .androidTV: service = AndroidTVProvider()
    case .samsung: service = SamsungProvider()
    case .webOS: service = WebOSProvider()
    }
    provider = service
    service.update = { [weak self] state in
      guard let self, g == self.generation else { return }
      let wasReady = self.connection.phase == .ready
      self.connection = state
      if state.phase == .ready {
        if !wasReady || !self.devices.contains(tv) {
          self.devices.removeAll {
            $0.id == tv.id || $0.host == tv.host && $0.platform == tv.platform
          }
          self.devices.append(tv)
          self.persist()
        }
        if self.autoKeyboard, let request = state.keyboardRequest, request != self.lastKeyboard {
          self.lastKeyboard = request
          self.sheet = .keyboard
        }
      } else {
        self.stopHold()
      }
      if state.keyboard?.mode == .closed, self.sheet == .keyboard { self.sheet = nil }
      if state.phase == .error, wasReady, state.retryable, !self.reconnectUsed, self.foreground {
        self.reconnectUsed = true
        self.connectTask = Task {
          try? await Task.sleep(for: .seconds(1))
          guard g == self.generation, !Task.isCancelled else { return }
          self.connect(tv, automatic: true)
        }
      }
    }
    connectTask = Task { await service.connect(tv) }
  }
  func pair(_ code: String) async {
    do { try await provider?.pair(code) } catch { message = error.localizedDescription }
  }
  func reconnect() { if let selected { connect(selected) } }
  func leaveRemote() {
    generation += 1
    connectTask?.cancel()
    stopHold()
    provider?.disconnect()
    provider = nil
    selected = nil
    connection = ConnectionState()
    defaults.removeObject(forKey: "last-tv")
  }
  func forget(_ tv: TVDevice) {
    if selected?.id == tv.id { leaveRemote() }
    devices.removeAll { $0.id == tv.id }
    profiles.removeValue(forKey: tv.id)
    for prefix in [
      "android-pin", "samsung-pin", "samsung-token", "samsung-mode", "lg",
    ] {
      SecureStore.remove("\(prefix):\(tv.id)")
    }
    persist()
  }
  func confirmIdentity() {
    guard let tv = selected, let fingerprint = connection.fingerprint else { return }
    do {
      try SecureStore.write("samsung-pin:\(tv.id)", fingerprint)
      reconnect()
    } catch { message = error.localizedDescription }
  }
  func legacySamsung() {
    guard let tv = selected, connection.legacyOffer else { return }
    do {
      try SecureStore.write("samsung-mode:\(tv.id)", "legacy")
      reconnect()
    } catch { message = error.localizedDescription }
  }
  func secureSamsung() {
    guard let tv = selected else { return }
    SecureStore.remove("samsung-mode:\(tv.id)")
    reconnect()
  }
  func resetSamsung() {
    guard let tv = selected, connection.resetAllowed else { return }
    for p in ["samsung-pin", "samsung-token", "samsung-mode"] {
      SecureStore.remove("\(p):\(tv.id)")
    }
    reconnect()
  }
  func dispatch(_ command: RemoteCommand) {
    guard connection.phase == .ready, caps.commands.contains(command), !sending, let provider else {
      return
    }
    let g = generation
    sending = true
    Task {
      do { try await provider.command(command) } catch {
        if g == generation {
          message = L10n.format("Could not send %@. Check the connection.", command.title)
        }
      }
      if g == generation { sending = false }
    }
  }
  func canHold(_ command: RemoteCommand) -> Bool {
    command.repeats || provider?.nativeHold(command) == true
  }
  func nativeHold(_ command: RemoteCommand) -> Bool { provider?.nativeHold(command) == true }
  private var holdOwner: UUID?
  func startHold(_ command: RemoteCommand, token: UUID) {
    holdOwner = token
    holdSequence.stop()
    guard let provider, foreground, connection.phase == .ready, caps.commands.contains(command)
    else { return }
    let g = generation
    holdSequence.start { [self] in
      guard !Task.isCancelled, g == generation, connection.phase == .ready else { return }
      if provider.nativeHold(command) {
        do {
          try await provider.beginHold(command)
          while !Task.isCancelled { try await Task.sleep(for: .seconds(60)) }
        } catch {
          if !Task.isCancelled, g == generation {
            message = L10n.format("Could not send %@. Check the connection.", command.title)
          }
        }
        try? await provider.endHold(command)
      } else {
        dispatch(command)
        guard command.repeats else { return }
        try? await Task.sleep(for: .milliseconds(400))
        while !Task.isCancelled, g == generation, connection.phase == .ready {
          dispatch(command)
          try? await Task.sleep(
            for: .milliseconds(command == .rewind || command == .forward ? 250 : 130))
        }
      }
    }
  }
  func stopHold(token: UUID? = nil) {
    if let token, token != holdOwner { return }
    holdOwner = nil
    holdSequence.stop()
  }
  func sendText(_ value: String, session: Int? = nil, connection owner: Int) async throws {
    guard caps.text, connection.phase == .ready, let provider else {
      throw RemoteError.message(L10n.text("Reconnect to the TV before sending text"))
    }
    let g = generation
    guard !Task.isCancelled, owner == generation,
      session == nil || session == connection.keyboard?.session
    else { throw CancellationError() }
    try await provider.text(value)
    guard g == generation, connection.phase == .ready else {
      throw RemoteError.message(
        L10n.text("The TV connection changed. Check the selected TV before retrying."))
    }
  }
  func editKeyboard(
    _ value: IMETextValue, session: Int, connection owner: Int, composing: Bool = false
  ) {
    guard owner == generation, foreground, connection.phase == .ready else { return }
    provider?.editIME(value, session: session, composing: composing)
  }
  func submitKeyboard(session: Int, connection owner: Int) async throws {
    guard owner == generation, foreground, connection.phase == .ready, let provider else {
      throw CancellationError()
    }
    try await provider.submitIME(session: session)
  }
  func stopKeyboardSync(session: Int?, connection owner: Int) {
    guard owner == generation, session == nil || session == connection.keyboard?.session else {
      return
    }
    provider?.stopIME()
  }
  func cancelKeyboardForLifecycle() { provider?.stopIME() }
  func launch(_ app: AppShortcut) {
    guard caps.apps, app.valid else { return }
    Task {
      do { try await provider?.launch(app) } catch {
        message = L10n.text("TV could not open that link. Check the app's supported launch route.")
      }
    }
  }
  func move(_ x: Double, _ y: Double) {
    guard caps.pointer, connection.phase == .ready, !sending, let provider else { return }
    let g = generation
    sending = true
    Task {
      defer { if g == generation { sending = false } }
      do { try await provider.move(x, y) } catch {
        if g == generation {
          message = L10n.text("TV pointer disconnected. Reconnect to restore it.")
        }
      }
    }
  }
  func click() {
    guard connection.phase == .ready else { return }
    if caps.pointer {
      let g = generation
      Task {
        do { try await provider?.click() } catch {
          if g == generation { message = error.localizedDescription }
        }
      }
    } else {
      dispatch(.ok)
    }
  }
  func scroll(_ amount: Double) {
    guard caps.scroll, connection.phase == .ready, let provider else { return }
    let g = generation
    Task {
      do { try await provider.scroll(amount) } catch {
        if g == generation { message = error.localizedDescription }
      }
    }
  }
  func tvAction() {
    if let url = profile.tvApp, let app = profile.apps.first(where: { $0.target == url }) {
      launch(app)
    } else if let command = profile.tvCommand {
      dispatch(command)
    }
  }
  func setActive(_ active: Bool) {
    foreground = active
    if !active {
      generation += 1
      connectTask?.cancel()
      stopHold()
      provider?.disconnect()
      connection = ConnectionState(
        phase: .disconnected, message: L10n.text("Paused while OpenTV-Remote is in the background"))
    } else if let selected {
      connect(selected)
    }
  }
  func monitorNetwork() {
    guard path == nil else { return }
    let monitor = NWPathMonitor()
    path = monitor
    monitor.pathUpdateHandler = { [weak self] value in
      let signature = "\(value.status)-\(value.availableInterfaces.map(\.name).sorted().joined())"
      Task { @MainActor in
        guard let self else { return }
        defer { self.networkSignature = signature }
        guard let previous = self.networkSignature, previous != signature, self.foreground,
          self.selected != nil
        else { return }
        if value.status == .satisfied {
          self.reconnect()
        } else {
          self.generation += 1
          self.connectTask?.cancel()
          self.stopHold()
          self.provider?.disconnect()
          self.connection = ConnectionState(
            phase: .error, message: L10n.text("Network unavailable. Reconnect to your home Wi-Fi."))
        }
      }
    }
    monitor.start(queue: DispatchQueue(label: "luma.network"))
  }
  func refreshAddresses() {
    var changed = false
    for discovered in discovery.devices {
      if let index = devices.firstIndex(where: { $0.id == discovered.id }),
        devices[index].host != discovered.host
      {
        devices[index].host = discovered.host
        changed = true
      }
    }
    if changed { persist() }
  }
}
enum RemoteSheet: String, Identifiable {
  case devices, editor, presets, appearance, language, keyboard, numbers, allControls, apps,
    tvSetup, motion,
    about
  var id: String { rawValue }
}
