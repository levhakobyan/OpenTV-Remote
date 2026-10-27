import Darwin
import Foundation
import Network
import Observation

@MainActor @Observable
final class TVDiscovery: NSObject, @preconcurrency NetServiceBrowserDelegate,
  @preconcurrency NetServiceDelegate
{
  var devices = [TVDevice]()
  var scanning = false
  var issue: String?
  private var browsers = [NetServiceBrowser]()
  private var services = [NetService]()
  private var scanTask: Task<Void, Never>?
  private var generation = 0
  func start() {
    stop()
    generation += 1
    let g = generation
    devices = []
    issue = nil
    scanning = true
    for type in ["_androidtvremote2._tcp."] {
      let browser = NetServiceBrowser()
      browser.delegate = self
      browsers.append(browser)
      browser.searchForServices(ofType: type, inDomain: "local.")
    }
    scanTask = Task { [weak self] in
      let entries = await Task.detached(priority: .utility) { Self.ssdp() }.value
      guard let self, g == self.generation, !Task.isCancelled else { return }
      for (host, location) in entries { await self.describe(host: host, location: location, g: g) }
      guard g == self.generation else { return }
      self.scanning = false
      if self.devices.isEmpty, self.issue == nil {
        self.issue =
          L10n.text(
            "No TVs found yet. Keep the TV on, check Local Network access in Settings, or connect by IP. Some TVs don’t advertise on this network."
          )
      }
    }
  }
  func stop() {
    generation += 1
    scanTask?.cancel()
    scanTask = nil
    for browser in browsers { browser.stop() }
    browsers = []
    for service in services { service.stop() }
    services = []
    scanning = false
  }
  func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
    issue =
      L10n.text(
        "Local discovery is unavailable. Allow Local Network access in Settings, or connect by IP.")
  }
  func netServiceBrowser(
    _ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool
  ) {
    service.delegate = self
    services.append(service)
    service.resolve(withTimeout: 5)
  }
  func netServiceDidResolveAddress(_ sender: NetService) {
    for data in sender.addresses ?? [] {
      var storage = sockaddr_storage()
      _ = withUnsafeMutableBytes(of: &storage) { data.copyBytes(to: $0) }
      guard storage.ss_family == sa_family_t(AF_INET) else { continue }
      var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
      let result = withUnsafePointer(to: &storage) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
          getnameinfo(
            $0, socklen_t(data.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
        }
      }
      let address = String(cString: host)
      if result == 0, LocalAddress.valid(address, allowLoopback: Self.simulator) {
        add(
          TVDevice(
            id: "android:\(sender.name)", name: String(sender.name.prefix(64)), host: address,
            platform: .androidTV))
        break
      }
    }
  }
  func add(_ tv: TVDevice) {
    devices.removeAll { $0.id == tv.id || ($0.host == tv.host && $0.platform == tv.platform) }
    if devices.count < 64 { devices.append(tv) }
  }
  static var simulator: Bool {
    #if targetEnvironment(simulator)
      true
    #else
      false
    #endif
  }
  static func detect(_ host: String) async throws -> TVDevice {
    guard LocalAddress.valid(host, allowLoopback: simulator) else {
      throw RemoteError.message(L10n.text("Enter a local IPv4 address, such as 192.168.1.20"))
    }
    let probe = TVSession(host: host)
    defer { probe.close() }
    if let raw = try? await probe.metadata(URL(string: "http://\(host):8001/api/v2/")!),
      let (tv, _) = try? SamsungCodec.info(raw, host: host)
    {
      return tv
    }
    if await portOpen(host, 6467) {
      return TVDevice(id: "android:\(host)", name: "Android TV", host: host, platform: .androidTV)
    }
    if await portOpen(host, 3000) {
      return TVDevice(id: "webos:\(host)", name: "LG webOS", host: host, platform: .webOS)
    }
    if await portOpen(host, 8002) {
      return TVDevice(id: "samsung:\(host)", name: "Samsung TV", host: host, platform: .samsung)
    }
    throw RemoteError.message(
      L10n.text(
        "No supported TV service answered. Turn on the TV, use the same Wi-Fi and check the address."
      ))
  }
  private static func portOpen(_ host: String, _ port: UInt16) async -> Bool {
    await withCheckedContinuation { c in
      let queue = DispatchQueue(label: "luma.probe")
      let connection = NWConnection(
        host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
      let gate = CompletionGate()
      connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
          if gate.take() { c.resume(returning: true) }
          connection.cancel()
        case .failed, .cancelled: if gate.take() { c.resume(returning: false) }
        default: break
        }
      }
      connection.start(queue: queue)
      queue.asyncAfter(deadline: .now() + 1.3) { connection.cancel() }
    }
  }
  private func describe(host: String, location: String, g: Int) async {
    guard LocalAddress.valid(host, allowLoopback: Self.simulator), let u = URL(string: location),
      u.scheme == "http", u.host == host, u.user == nil
    else { return }
    let probe = TVSession(host: host)
    defer { probe.close() }
    guard let data = try? await probe.metadata(u), g == generation else { return }
    let parser = DeviceXML()
    let xml = XMLParser(data: data)
    xml.shouldResolveExternalEntities = false
    xml.delegate = parser
    guard xml.parse() else { return }
    let all = parser.values.values.joined(separator: " ").lowercased()
    let id = parser.values["UDN"] ?? host
    let name = String((parser.values["friendlyName"] ?? L10n.text("TV")).prefix(64))
    if all.contains("samsung")
      && (all.contains("mediarenderer") || all.contains("remotecontrolreceiver")
        || all.contains("smarttv") || all.contains("dtv"))
    {
      if let raw = try? await probe.metadata(URL(string: "http://\(host):8001/api/v2/")!),
        let (tv, _) = try? SamsungCodec.info(raw, host: host)
      {
        if g == generation { add(tv) }
      } else if g == generation {
        add(TVDevice(id: "samsung:\(id)", name: name, host: host, platform: .samsung))
      }
    } else if all.contains("webos") || all.contains("lg electronics") {
      add(TVDevice(id: "webos:\(id)", name: name, host: host, platform: .webOS))
    }
  }
  nonisolated private static func ssdp() -> [(String, String)] {
    let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    guard fd >= 0 else { return [] }
    defer { Darwin.close(fd) }
    var timeout = timeval(tv_sec: 1, tv_usec: 0)
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    var dest = sockaddr_in()
    dest.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    dest.sin_family = sa_family_t(AF_INET)
    dest.sin_port = UInt16(1900).bigEndian
    inet_pton(AF_INET, "239.255.255.250", &dest.sin_addr)
    let request = Data(
      "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
        .utf8)
    let sent = request.withUnsafeBytes { bytes in
      withUnsafePointer(to: &dest) { p in
        p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
          sendto(
            fd, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
      }
    }
    guard sent > 0 else { return [] }
    var result = [(String, String)]()
    var seen = Set<String>()
    let end = Date().addingTimeInterval(6)
    while Date() < end {
      var buffer = [UInt8](repeating: 0, count: 8192)
      var from = sockaddr_in()
      var length = socklen_t(MemoryLayout<sockaddr_in>.size)
      let count = withUnsafeMutablePointer(to: &from) { p in
        p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
          recvfrom(fd, &buffer, buffer.count, 0, $0, &length)
        }
      }
      if count <= 0 { continue }
      var ip = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
      inet_ntop(AF_INET, &from.sin_addr, &ip, socklen_t(ip.count))
      let host = String(cString: ip)
      let raw = String(decoding: buffer.prefix(count), as: UTF8.self)
      let headers = raw.components(separatedBy: "\r\n").compactMap { line -> (String, String)? in
        guard let colon = line.firstIndex(of: ":") else { return nil }
        return (
          String(line[..<colon]).lowercased(),
          line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        )
      }
      if let location = headers.first(where: { $0.0 == "location" })?.1,
        seen.insert(location).inserted, result.count < 64
      {
        result.append((host, location))
      }
    }
    return result
  }
}
private final class DeviceXML: NSObject, XMLParserDelegate {
  var values = [String: String]()
  private var current: String?
  func parser(
    _ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
    qualifiedName qName: String?, attributes: [String: String]
  ) {
    current =
      ["friendlyName", "manufacturer", "modelName", "UDN", "deviceType"].contains(elementName)
      ? elementName : nil
  }
  func parser(_ parser: XMLParser, foundCharacters string: String) {
    if let current { values[current, default: ""] += string }
  }
  func parser(
    _ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
    qualifiedName qName: String?
  ) { current = nil }
}
