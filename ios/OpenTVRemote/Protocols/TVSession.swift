import Foundation
import Security

final class TVSession: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
  let host: String
  let pin: String?
  let observed = CertificateBox()
  lazy var session: URLSession = {
    let c = URLSessionConfiguration.ephemeral
    c.timeoutIntervalForRequest = 7
    c.timeoutIntervalForResource = 12
    c.urlCache = nil
    c.httpCookieStorage = nil
    return URLSession(configuration: c, delegate: self, delegateQueue: nil)
  }()
  init(host: String, pin: String? = nil) {
    self.host = host
    self.pin = pin
  }
  func urlSession(
    _ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
  ) {
    handle(challenge, completionHandler)
  }
  func urlSession(
    _ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
  ) {
    handle(challenge, completionHandler)
  }
  private func handle(
    _ challenge: URLAuthenticationChallenge,
    _ completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
  ) {
    guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
      challenge.protectionSpace.host == host, let trust = challenge.protectionSpace.serverTrust
    else {
      completionHandler(.performDefaultHandling, nil)
      return
    }
    // Build the chain even for a self-signed peer; trust still requires our explicit pin below.
    _ = SecTrustEvaluateWithError(trust, nil)
    guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
      let cert = chain.first
    else {
      completionHandler(.cancelAuthenticationChallenge, nil)
      return
    }
    let data = SecCertificateCopyData(cert) as Data
    observed.set(data)
    if let pin, pin == SecureStore.fingerprint(data) {
      completionHandler(.useCredential, URLCredential(trust: trust))
    } else {
      completionHandler(.cancelAuthenticationChallenge, nil)
    }
  }
  func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) { completionHandler(nil) }
  func metadata(_ url: URL) async throws -> Data {
    guard url.host == host, url.user == nil, url.password == nil else {
      throw RemoteError.message(L10n.text("Invalid TV endpoint"))
    }
    let (bytes, response) = try await session.bytes(for: URLRequest(url: url))
    guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
      http.expectedContentLength <= 65536
    else { throw RemoteError.message(L10n.text("TV metadata unavailable")) }
    var data = Data()
    for try await byte in bytes {
      guard data.count < 65536 else {
        throw RemoteError.message(L10n.text("TV metadata too large"))
      }
      data.append(byte)
    }
    return data
  }
  func close() { session.invalidateAndCancel() }
}

@MainActor final class SamsungProvider: RemoteProvider {
  var update: ((ConnectionState) -> Void)?
  private var tv: TVDevice?
  private var transport: TVSession?
  private var socket: URLSessionWebSocketTask?
  private var reader: Task<Void, Never>?
  private var timeout: Task<Void, Never>?
  private var generation = 0
  private var legacy = false
  private var caps = Capabilities()
  private var state = ConnectionState()
  private func emit(_ value: ConnectionState) {
    state = value
    update?(value)
  }
  func connect(_ tv: TVDevice) async {
    disconnect()
    self.tv = tv
    let g = generation
    legacy = SecureStore.read("samsung-mode:\(tv.id)") == "legacy"
    emit(ConnectionState(phase: .connecting, message: L10n.text("Checking Samsung TV…")))
    let pin = SecureStore.read("samsung-pin:\(tv.id)")
    let http = TVSession(host: tv.host, pin: pin)
    transport = http
    do {
      let raw = try await http.metadata(
        URL(string: "\(legacy ? "http":"https")://\(tv.host):\(legacy ? 8001:8002)/api/v2/")!)
      guard g == generation else { return }
      caps = try SamsungCodec.info(raw, host: tv.host).1
      open(g, token: legacy ? nil : SecureStore.read("samsung-token:\(tv.id)"), retried: false)
    } catch {
      guard g == generation else { return }
      if !legacy, let observed = http.observed.get() {
        let fingerprint = SecureStore.fingerprint(observed)
        if pin == nil {
          emit(
            ConnectionState(
              phase: .connecting,
              message: L10n.text("Confirm this TV on your trusted home network"),
              fingerprint: fingerprint, retryable: false))
          return
        }
        if pin != fingerprint {
          emit(
            ConnectionState(
              phase: .error,
              message:
                L10n.text(
                  "TV identity changed. Reset pairing only if you expect this TV update or reset."),
              retryable: false, resetAllowed: true))
          return
        }
      }
      var offer = false
      if !legacy {
        let probe = TVSession(host: tv.host)
        defer { probe.close() }
        if let raw = try? await probe.metadata(URL(string: "http://\(tv.host):8001/api/v2/")!),
          (try? SamsungCodec.info(raw, host: tv.host)) != nil
        {
          offer = true
        }
      }
      guard g == generation else { return }
      emit(
        ConnectionState(
          phase: .error,
          message:
            L10n.text(
              "Samsung connection unavailable. Turn on the TV and check its remote permissions and Wi-Fi."
            ),
          legacyOffer: offer, legacy: legacy))
    }
  }
  private func open(_ g: Int, token: String?, retried: Bool) {
    guard let tv, let transport, g == generation else { return }
    socket?.cancel(with: .goingAway, reason: nil)
    timeout?.cancel()
    var components = URLComponents()
    components.scheme = legacy ? "ws" : "wss"
    components.host = tv.host
    components.port = legacy ? 8001 : 8002
    components.path = "/api/v2/channels/samsung.remote.control"
    components.queryItems = [
      URLQueryItem(name: "name", value: Data("OpenTV-Remote".utf8).base64EncodedString())
    ]
    if !legacy, let token, SamsungCodec.validToken(token) {
      components.queryItems?.append(URLQueryItem(name: "token", value: token))
    }
    let ws = transport.session.webSocketTask(with: components.url!)
    ws.maximumMessageSize = 65536
    socket = ws
    ws.resume()
    emit(
      ConnectionState(
        phase: .pairing,
        message: token == nil
          ? L10n.text("Allow OpenTV-Remote on your TV")
          : L10n.text("Restoring Samsung TV permission…"),
        legacy: legacy))
    timeout = Task { [weak self] in
      try? await Task.sleep(for: .seconds(60))
      guard !Task.isCancelled, let self, g == self.generation, self.state.phase != .ready else {
        return
      }
      self.fail(
        L10n.text("TV approval timed out. Reconnect and allow OpenTV-Remote on the TV."),
        retryable: false)
    }
    reader = Task { [weak self] in
      do {
        while !Task.isCancelled {
          let response = try await ws.receive()
          guard let self, g == self.generation, self.socket === ws else { return }
          let raw: Data
          switch response {
          case .string(let s): raw = Data(s.utf8)
          case .data(let d): raw = d
          @unknown default: continue
          }
          guard raw.count <= 65536,
            let o = try JSONSerialization.jsonObject(with: raw) as? [String: Any]
          else { throw RemoteError.message(L10n.text("Invalid Samsung response")) }
          switch o["event"] as? String {
          case "ms.channel.connect":
            guard let data = o["data"] as? [String: Any] else {
              throw RemoteError.message(L10n.text("Missing TV approval"))
            }
            if !self.legacy, let value = data["token"], !(value is NSNull) {
              guard value is String || value is NSNumber else {
                throw RemoteError.message(L10n.text("Invalid TV token"))
              }
              let token = String(describing: value)
              guard SamsungCodec.validToken(token) else {
                throw RemoteError.message(L10n.text("Invalid TV token"))
              }
              try SecureStore.write("samsung-token:\(tv.id)", token)
            }
            self.timeout?.cancel()
            self.emit(
              ConnectionState(
                phase: .ready,
                message: self.legacy
                  ? L10n.text("Connected · unencrypted legacy Wi-Fi")
                  : L10n.text("Connected · secure Samsung Wi-Fi"),
                capabilities: self.caps, legacy: self.legacy))
          case "ms.channel.unauthorized":
            if token != nil && !retried && !self.legacy {
              SecureStore.remove("samsung-token:\(tv.id)")
              self.open(g, token: nil, retried: true)
              return
            }
            self.fail(
              L10n.text(
                "TV declined permission. Allow OpenTV-Remote in Device Connection Manager, then reconnect."
              ),
              retryable: false)
            return
          case "ms.channel.timeOut", "ms.error":
            self.fail(
              L10n.text("TV declined or timed out. Reconnect and approve the TV prompt."),
              retryable: false)
            return
          default: break
          }
        }
      } catch {
        guard let self, !Task.isCancelled, g == self.generation, self.socket === ws else { return }
        self.fail(
          L10n.text("Samsung connection lost. Check TV power, Wi-Fi and remote permissions."))
      }
    }
  }
  private func fail(_ message: String, retryable: Bool = true) {
    timeout?.cancel()
    socket?.cancel(with: .goingAway, reason: nil)
    socket = nil
    emit(ConnectionState(phase: .error, message: message, legacy: legacy, retryable: retryable))
  }
  func command(_ command: RemoteCommand) async throws {
    guard state.phase == .ready, caps.commands.contains(command), let socket else {
      throw RemoteError.message(L10n.text("Command unavailable"))
    }
    try await socket.send(.string(try SamsungCodec.key(command)))
  }
  func text(_ value: String) async throws {
    guard state.phase == .ready, caps.text, let socket else {
      throw RemoteError.message(L10n.text("Text entry unavailable"))
    }
    for packet in try SamsungCodec.text(value) { try await socket.send(.string(packet)) }
  }
  func disconnect() {
    generation += 1
    timeout?.cancel()
    reader?.cancel()
    reader = nil
    socket?.cancel(with: .goingAway, reason: nil)
    socket = nil
    transport?.close()
    transport = nil
  }
}
