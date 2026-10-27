import Foundation

@MainActor protocol RemoteProvider: AnyObject {
  var update: ((ConnectionState) -> Void)? { get set }
  func connect(_ tv: TVDevice) async
  func pair(_ code: String) async throws
  func command(_ command: RemoteCommand) async throws
  func nativeHold(_ command: RemoteCommand) -> Bool
  func beginHold(_ command: RemoteCommand) async throws
  func endHold(_ command: RemoteCommand) async throws
  func text(_ value: String) async throws
  func editIME(_ value: IMETextValue, session: Int, composing: Bool)
  func submitIME(session: Int) async throws
  func stopIME()
  func launch(_ app: AppShortcut) async throws
  func move(_ dx: Double, _ dy: Double) async throws
  func click() async throws
  func scroll(_ value: Double) async throws
  func disconnect()
}
extension RemoteProvider {
  func editIME(_ value: IMETextValue, session: Int, composing: Bool) {}
  func submitIME(session: Int) async throws {
    throw RemoteError.message(L10n.text("Live keyboard is unavailable. Use manual Send."))
  }
  func stopIME() {}
  func nativeHold(_ command: RemoteCommand) -> Bool { false }
  func beginHold(_ command: RemoteCommand) async throws {
    throw RemoteError.message(L10n.text("Held keys unavailable"))
  }
  func endHold(_ command: RemoteCommand) async throws {}
  func pair(_ code: String) async throws {
    throw RemoteError.message(L10n.text("Approve this connection on the TV"))
  }
  func text(_ value: String) async throws {
    throw RemoteError.message(L10n.text("Text entry unavailable"))
  }
  func launch(_ app: AppShortcut) async throws {
    throw RemoteError.message(L10n.text("App shortcuts unavailable"))
  }
  func move(_ dx: Double, _ dy: Double) async throws {
    throw RemoteError.message(L10n.text("Pointer unavailable"))
  }
  func click() async throws { try await command(.ok) }
  func scroll(_ value: Double) async throws {
    throw RemoteError.message(L10n.text("Scrolling unavailable"))
  }
}
