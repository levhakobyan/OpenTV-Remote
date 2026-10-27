import Foundation

enum EncodingRules {
  /// Canonical positive ASN.1 INTEGER content, including only a necessary sign byte.
  static func positiveInteger(_ raw: Data) -> Data {
    var value = Data(raw.drop { $0 == 0 })
    if value.isEmpty { return Data([0]) }
    if value[0] & 0x80 != 0 { value.insert(0, at: 0) }
    return value
  }
}
