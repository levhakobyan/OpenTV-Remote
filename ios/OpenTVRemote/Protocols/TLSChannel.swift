import Foundation
import Network
import Security

final class CertificateBox: @unchecked Sendable {
  private let lock = NSLock()
  private var value: Data?
  func set(_ data: Data) {
    lock.lock()
    value = data
    lock.unlock()
  }
  func get() -> Data? {
    lock.lock()
    defer { lock.unlock() }
    return value
  }
}
final class CompletionGate: @unchecked Sendable {
  private let lock = NSLock()
  private var completed = false
  func take() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    if completed { return false }
    completed = true
    return true
  }
  var pending: Bool {
    lock.lock()
    defer { lock.unlock() }
    return !completed
  }
}
/// A single reader consumes bounded Remote v2 frames; sends are complete framed writes.
final class TLSChannel: @unchecked Sendable {
  let connection: NWConnection
  let peer = CertificateBox()
  private var buffer = Data()
  private let frameLimit: Int
  private let queue = DispatchQueue(label: "com.opentvremote.tls")
  init(
    host: String, port: UInt16, identity: SecIdentity? = nil, pin: String?,
    frameLimit: Int = 1_048_576
  ) throws {
    self.frameLimit = frameLimit
    let options = NWProtocolTLS.Options()
    if let identity {
      guard let localIdentity = sec_identity_create(identity) else {
        throw RemoteError.message(L10n.text("Pairing identity unavailable"))
      }
      sec_protocol_options_set_local_identity(options.securityProtocolOptions, localIdentity)
    }
    sec_protocol_options_set_min_tls_protocol_version(options.securityProtocolOptions, .TLSv12)
    let box = peer
    sec_protocol_options_set_verify_block(
      options.securityProtocolOptions,
      { _, trust, complete in
        let trustRef = sec_trust_copy_ref(trust).takeRetainedValue()
        guard let chain = SecTrustCopyCertificateChain(trustRef) as? [SecCertificate],
          let cert = chain.first
        else {
          complete(false)
          return
        }
        let data = SecCertificateCopyData(cert) as Data
        box.set(data)
        complete(pin == nil || pin == SecureStore.fingerprint(data))
      }, queue)
    connection = NWConnection(
      host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!,
      using: NWParameters(tls: options, tcp: NWProtocolTCP.Options()))
  }
  func start() async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      let gate = CompletionGate()
      connection.stateUpdateHandler = { state in
        switch state {
        case .ready: if gate.take() { continuation.resume() }
        case .failed, .cancelled:
          if gate.take() {
            continuation.resume(
              throwing: RemoteError.message(
                L10n.text("Secure TV connection failed. Check Wi-Fi, power and pairing.")))
          }
        default: break
        }
      }
      connection.start(queue: queue)
      queue.asyncAfter(deadline: .now() + 8) { [weak self] in
        if gate.pending { self?.connection.cancel() }
      }
    }
  }
  func send(_ body: Data) async throws {
    guard body.count <= frameLimit else {
      throw RemoteError.message(L10n.text("TV message too large"))
    }
    try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
      connection.send(
        content: Wire.varint(UInt64(body.count)) + body,
        completion: .contentProcessed { error in
          if let error { c.resume(throwing: error) } else { c.resume() }
        })
    }
  }
  func receive() async throws -> Data {
    while true {
      if !buffer.isEmpty {
        var index = 0
        if let size = try? Wire.readVarint(buffer, &index) {
          guard size > 0, size <= UInt64(frameLimit) else {
            throw RemoteError.message(L10n.text("Invalid TV frame size"))
          }
          if buffer.count - index >= Int(size) {
            let frame = Data(buffer[index..<index + Int(size)])
            buffer = Data(buffer.dropFirst(index + Int(size)))
            return frame
          }
        } else if buffer.count >= 10 {
          throw RemoteError.message(L10n.text("Invalid TV frame header"))
        }
      }
      let chunk: Data = try await withCheckedThrowingContinuation { c in
        connection.receive(minimumIncompleteLength: 1, maximumLength: min(65536, frameLimit + 10)) {
          data, _, done, error in
          if let error {
            c.resume(throwing: error)
          } else if let data, !data.isEmpty {
            c.resume(returning: data)
          } else {
            c.resume(
              throwing: RemoteError.message(
                done ? L10n.text("TV disconnected") : L10n.text("Empty TV frame")))
          }
        }
      }
      guard buffer.count + chunk.count <= frameLimit + 65536 else {
        throw RemoteError.message(L10n.text("TV frame exceeded limit"))
      }
      buffer += chunk
    }
  }
  func cancel() { connection.cancel() }
}
