import Foundation

enum KeyDirection: Int {
  case down = 1
  case up = 2
  case short = 3
}

enum Wire {
  struct Field {
    let number: Int
    var value: UInt64 = 0
    var bytes: Data?
  }
  static func varint(_ value: UInt64) -> Data {
    var n = value
    var out = Data()
    while n > 127 {
      out.append(UInt8(n & 127) | 128)
      n >>= 7
    }
    out.append(UInt8(n))
    return out
  }
  static func integer(_ n: Int, _ v: Int) -> Data {
    varint(UInt64(n * 8)) + varint(UInt64(bitPattern: Int64(v)))
  }
  static func bytes(_ n: Int, _ data: Data) -> Data {
    varint(UInt64(n * 8 + 2)) + varint(UInt64(data.count)) + data
  }
  static func string(_ n: Int, _ text: String) -> Data { bytes(n, Data(text.utf8)) }
  static func readVarint(_ data: Data, _ offset: inout Int) throws -> UInt64 {
    var result: UInt64 = 0
    for i in 0..<10 {
      guard offset < data.count else { throw RemoteError.message(L10n.text("Incomplete frame")) }
      let b = data[data.startIndex + offset]
      offset += 1
      guard i < 9 || b <= 1 else { throw RemoteError.message(L10n.text("Invalid varint")) }
      result |= UInt64(b & 127) << (7 * i)
      if b & 128 == 0 { return result }
    }
    throw RemoteError.message(L10n.text("Invalid varint"))
  }
  static func parse(_ data: Data) throws -> [Field] {
    guard data.count <= 1_048_576 else {
      throw RemoteError.message(L10n.text("Oversized TV frame"))
    }
    var offset = 0
    var fields = [Field]()
    while offset < data.count {
      let tag = try readVarint(data, &offset)
      guard tag >> 3 > 0, tag >> 3 <= 536_870_911 else {
        throw RemoteError.message(L10n.text("Invalid field"))
      }
      let n = Int(tag >> 3)
      switch tag & 7 {
      case 0: fields.append(Field(number: n, value: try readVarint(data, &offset)))
      case 2:
        let length = try readVarint(data, &offset)
        guard length <= 1_048_576, length <= data.count - offset else {
          throw RemoteError.message(L10n.text("Invalid field length"))
        }
        let end = offset + Int(length)
        fields.append(Field(number: n, bytes: Data(data[offset..<end])))
        offset = end
      case 1, 5:
        offset += tag & 7 == 1 ? 8 : 4
        guard offset <= data.count else {
          throw RemoteError.message(L10n.text("Incomplete fixed field"))
        }
      default: throw RemoteError.message(L10n.text("Unsupported TV frame"))
      }
    }
    return fields
  }
  static func key(_ c: RemoteCommand, direction: KeyDirection = .short) -> Data {
    bytes(10, integer(1, c.androidKey) + integer(2, direction.rawValue))
  }
}
extension Array where Element == Wire.Field {
  func number(_ n: Int) -> Int { Int(truncatingIfNeeded: first { $0.number == n }?.value ?? 0) }
  func nested(_ n: Int) throws -> [Wire.Field] {
    try Wire.parse(first { $0.number == n }?.bytes ?? Data())
  }
  func has(_ n: Int) -> Bool { contains { $0.number == n } }
}
