import CryptoKit
import Foundation
import Security

enum SecureStore {
  static let service = "com.luma.remote.credentials"
  static func read(_ key: String) -> String? {
    var result: CFTypeRef?
    let status = SecItemCopyMatching(
      [
        kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: key,
        kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne,
      ] as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else { return nil }
    return String(data: data, encoding: .utf8)
  }
  static func write(_ key: String, _ value: String) throws {
    let q: [CFString: Any] = [
      kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: key,
    ]
    let attrs: [CFString: Any] = [
      kSecValueData: Data(value.utf8),
      kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]
    var status = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
    if status == errSecItemNotFound {
      status = SecItemAdd(q.merging(attrs) { _, b in b } as CFDictionary, nil)
    }
    guard status == errSecSuccess else {
      throw RemoteError.message(
        L10n.text("Could not securely save TV permission. Unlock your phone and retry."))
    }
  }
  static func remove(_ key: String) {
    SecItemDelete(
      [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: key]
        as CFDictionary)
  }
  /// Remove only the retired receiver's generic-password accounts, including orphaned ones.
  static func removeRetiredReceiverCredentials() {
    var result: CFTypeRef?
    let status = SecItemCopyMatching(
      [
        kSecClass: kSecClassGenericPassword, kSecAttrService: service,
        kSecReturnAttributes: true, kSecMatchLimit: kSecMatchLimitAll,
      ] as CFDictionary, &result)
    guard status == errSecSuccess, let entries = result as? [[String: Any]] else { return }
    for entry in entries {
      if let account = entry[kSecAttrAccount as String] as? String,
        SavedTVMigration.isRetiredCredential(account)
      {
        remove(account)
      }
    }
  }
  static func fingerprint(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  }
}

/// The RSA key stays in this app's Keychain; the self-signed public certificate identifies Remote v2 clients.
enum ClientIdentity {
  static let label = "com.luma.remote.androidtv.identity"
  static func load() throws -> SecIdentity {
    func find() -> SecIdentity? {
      var item: CFTypeRef?
      let status = SecItemCopyMatching(
        [
          kSecClass: kSecClassIdentity, kSecAttrLabel: label, kSecReturnRef: true,
          kSecMatchLimit: kSecMatchLimitOne,
        ] as CFDictionary, &item)
      return status == errSecSuccess ? (item as! SecIdentity) : nil
    }
    if let identity = find() {
      let certificate = try certificate(identity)
      let fields = try DER.children(DER.wrap(0x30, DER.children(certificate)[0]))
      let serial = fields[1]
      // DER INTEGERs must not contain redundant positive sign padding.
      if serial.count < 2 || serial[0] != 0 || serial[1] & 0x80 != 0 { return identity }
      SecItemDelete([kSecClass: kSecClassCertificate, kSecAttrLabel: label] as CFDictionary)
    }
    var found: CFTypeRef?
    let tag = Data(label.utf8)
    let status = SecItemCopyMatching(
      [
        kSecClass: kSecClassKey, kSecAttrApplicationTag: tag, kSecReturnRef: true,
        kSecAttrKeyType: kSecAttrKeyTypeRSA,
      ] as CFDictionary, &found)
    let key: SecKey
    if status == errSecSuccess {
      key = found as! SecKey
    } else {
      var error: Unmanaged<CFError>?
      guard
        let created = SecKeyCreateRandomKey(
          [
            kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits: 2048,
            kSecPrivateKeyAttrs: [
              kSecAttrIsPermanent: true, kSecAttrApplicationTag: tag, kSecAttrLabel: label,
              kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            ],
          ] as CFDictionary, &error)
      else { throw RemoteError.message(L10n.text("Could not create the phone's pairing identity")) }
      key = created
    }
    guard let publicKey = SecKeyCopyPublicKey(key),
      let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?
    else { throw RemoteError.message(L10n.text("Pairing identity unavailable")) }
    let algorithm = Data([
      0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0,
    ])
    let rsaAlgorithm = Data([
      0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0,
    ])
    let subject = DER.wrap(
      0x30,
      DER.wrap(
        0x31,
        DER.wrap(
          0x30, Data([0x06, 0x03, 0x55, 0x04, 0x03]) + DER.wrap(0x0c, Data("OpenTV-Remote".utf8)))))
    var random = [UInt8](repeating: 0, count: 16)
    guard SecRandomCopyBytes(kSecRandomDefault, random.count, &random) == errSecSuccess else {
      throw RemoteError.message(L10n.text("Secure randomness unavailable"))
    }
    let validity = DER.wrap(
      0x30, DER.wrap(0x17, Data("200101000000Z".utf8)) + DER.wrap(0x17, Data("400101000000Z".utf8)))
    let body = DER.wrap(
      0x30,
      Data([0xa0, 0x03, 0x02, 0x01, 0x02])
        + DER.wrap(0x02, EncodingRules.positiveInteger(Data(random))) + algorithm + subject
        + validity + subject + DER.wrap(0x30, rsaAlgorithm + DER.wrap(0x03, Data([0]) + raw)))
    guard
      let signature = SecKeyCreateSignature(
        key, .rsaSignatureMessagePKCS1v15SHA256, body as CFData, nil) as Data?,
      let cert = SecCertificateCreateWithData(
        nil, DER.wrap(0x30, body + algorithm + DER.wrap(0x03, Data([0]) + signature)) as CFData)
    else { throw RemoteError.message(L10n.text("Could not sign pairing identity")) }
    let added = SecItemAdd(
      [kSecClass: kSecClassCertificate, kSecValueRef: cert, kSecAttrLabel: label] as CFDictionary,
      nil)
    guard added == errSecSuccess || added == errSecDuplicateItem, let identity = find() else {
      throw RemoteError.message(L10n.text("Could not load the Keychain pairing identity"))
    }
    return identity
  }
  static func rsaParts(_ cert: Data) throws -> Data {
    guard let certificate = SecCertificateCreateWithData(nil, cert as CFData),
      let key = SecCertificateCopyKey(certificate),
      let raw = SecKeyCopyExternalRepresentation(key, nil) as Data?
    else { throw RemoteError.message(L10n.text("TV pairing requires an RSA certificate")) }
    let sequence = try DER.children(raw)
    guard sequence.count == 2 else {
      throw RemoteError.message(L10n.text("Invalid RSA public key"))
    }
    return sequence.reduce(Data()) { partial, part in partial + Data(part.drop { $0 == 0 }) }
  }
  static func certificate(_ identity: SecIdentity) throws -> Data {
    var cert: SecCertificate?
    guard SecIdentityCopyCertificate(identity, &cert) == errSecSuccess, let cert else {
      throw RemoteError.message(L10n.text("Missing pairing certificate"))
    }
    return SecCertificateCopyData(cert) as Data
  }
}
enum DER {
  static func wrap(_ tag: UInt8, _ data: Data) -> Data {
    var n = data.count
    var length = Data()
    if n < 128 {
      length.append(UInt8(n))
    } else {
      var bytes = [UInt8]()
      while n > 0 {
        bytes.insert(UInt8(n & 255), at: 0)
        n >>= 8
      }
      length.append(0x80 | UInt8(bytes.count))
      length.append(contentsOf: bytes)
    }
    return Data([tag]) + length + data
  }
  static func children(_ data: Data) throws -> [Data] {
    var offset = 0
    func next(_ bytes: Data, _ index: inout Int) throws -> Data {
      guard index + 2 <= bytes.count else {
        throw RemoteError.message(L10n.text("Invalid certificate"))
      }
      index += 1
      var count = Int(bytes[index])
      index += 1
      if count & 128 != 0 {
        let n = count & 127
        guard n > 0, n <= 4, index + n <= bytes.count else {
          throw RemoteError.message(L10n.text("Invalid certificate length"))
        }
        count = 0
        for _ in 0..<n {
          count = (count << 8) | Int(bytes[index])
          index += 1
        }
      }
      guard count <= bytes.count - index else {
        throw RemoteError.message(L10n.text("Invalid certificate length"))
      }
      let value = Data(bytes[index..<index + count])
      index += count
      return value
    }
    let body = try next(data, &offset)
    var cursor = 0
    var parts = [Data]()
    while cursor < body.count { parts.append(try next(body, &cursor)) }
    return parts
  }
}
