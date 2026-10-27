import Foundation

enum CanvasKind: String, Codable, CaseIterable, Identifiable {
  case key, dpad, touch, app, keyboard, numbers, tv, volume, channels, playback, navigation
  var id: String { rawValue }
  var title: String {
    switch self {
    case .key: L10n.text("Button")
    case .dpad: L10n.text("D-pad")
    case .touch: L10n.text("Touchpad")
    case .app: L10n.text("App shortcut")
    case .keyboard: L10n.text("Keyboard")
    case .numbers: L10n.text("Number pad")
    case .tv: L10n.text("TV action")
    case .volume: L10n.text("Volume rocker")
    case .channels: L10n.text("Channel rocker")
    case .playback: L10n.text("Playback")
    case .navigation: L10n.text("Back & Home")
    }
  }
  var minW: Int {
    switch self {
    case .dpad: 4
    case .touch, .numbers, .playback: 3
    case .app, .keyboard, .tv, .navigation: 2
    default: 1
    }
  }
  var minH: Int {
    switch self {
    case .dpad, .numbers: 4
    case .touch: 3
    case .volume, .channels: 2
    default: 1
    }
  }
  var keys: [RemoteCommand] {
    switch self {
    case .volume: [.volumeUp, .volumeDown]
    case .channels: [.channelUp, .channelDown]
    case .playback: [.rewind, .playPause, .forward]
    case .navigation: [.back, .home]
    default: []
    }
  }
  var vertical: Bool { self == .volume || self == .channels }
  var symbol: String {
    switch self {
    case .dpad: "dpad"
    case .touch: "hand.draw"
    case .keyboard: "keyboard"
    case .numbers: "circle.grid.3x3.fill"
    case .volume: "speaker.wave.2"
    case .channels: "plusminus"
    case .playback: "playpause"
    case .navigation: "arrow.uturn.backward"
    case .tv: "tv"
    case .app: "play.rectangle"
    case .key: "circle"
    }
  }
}
struct CanvasItem: Codable, Identifiable, Equatable {
  var id = UUID().uuidString
  var kind: CanvasKind
  var x: Int
  var y: Int
  var w: Int
  var h: Int
  var command: RemoteCommand?
  var app: String?
  init(
    id: String = UUID().uuidString, kind: CanvasKind, x: Int, y: Int, w: Int? = nil, h: Int? = nil,
    command: RemoteCommand? = nil, app: String? = nil
  ) {
    self.id = id
    self.kind = kind
    self.x = x
    self.y = y
    self.w = w ?? kind.minW
    self.h = h ?? kind.minH
    self.command = command
    self.app = app
  }
  func overlaps(_ b: Self) -> Bool { x < b.x + b.w && x + w > b.x && y < b.y + b.h && y + h > b.y }
  var valid: Bool {
    !id.isEmpty && id.count <= 80 && x >= 0 && y >= 0 && w >= kind.minW && h >= kind.minH
      && x + w <= 6 && y + h <= 24 && (kind != .key || command != nil)
      && (kind != .app || app != nil)
  }
  func supported(_ caps: Capabilities, _ apps: [AppShortcut]) -> Bool {
    switch kind {
    case .key: command.map(caps.commands.contains) ?? false
    case .dpad: caps.navigation
    case .touch: caps.navigation || caps.pointer
    case .keyboard: caps.text
    case .numbers: Set(RemoteCommand.digits).isSubset(of: caps.commands)
    case .app: caps.apps && apps.contains { $0.target == app }
    case .tv: caps.commands.contains(.tv) || caps.commands.contains(.input) || caps.apps
    default: Set(kind.keys).isSubset(of: caps.commands)
    }
  }
}
struct RemoteCanvas: Codable, Equatable {
  var items: [CanvasItem] = []
  var rows: Int { max(1, items.map { $0.y + $0.h }.max() ?? 0) }
  var valid: Bool {
    items.count <= 48 && Set(items.map(\.id)).count == items.count && items.allSatisfy(\.valid)
      && !items.enumerated().contains { index, item in
        items.dropFirst(index + 1).contains { item.overlaps($0) }
      }
  }
  var emptyRows: Int {
    (0..<rows).filter { row in !items.contains { ($0.y..<$0.y + $0.h).contains(row) } }.count
  }
  func removingEmptyRows() -> Self {
    let used = (0..<rows).filter { row in items.contains { ($0.y..<$0.y + $0.h).contains(row) } }
    return Self(
      items: items.map {
        var i = $0
        i.y = used.firstIndex(of: i.y) ?? i.y
        return i
      })
  }
  func placing(_ id: String, x: Int, y: Int) -> Self? {
    guard let old = items.first(where: { $0.id == id }) else { return nil }
    var moved = old
    moved.x = x
    moved.y = y
    let hits = items.filter { $0.id != id && moved.overlaps($0) }
    var result = self
    if hits.count == 1, let hit = hits.first, hit.x == x, hit.y == y, hit.w == old.w, hit.h == old.h
    {
      result.items = result.items.map { i in
        var i = i
        if i.id == hit.id {
          i.x = old.x
          i.y = old.y
        }
        return i
      }
    } else if !hits.isEmpty {
      return nil
    }
    result.items = result.items.map { $0.id == id ? moved : $0 }
    return result.valid ? result : nil
  }
  func resizing(_ id: String, w: Int, h: Int) -> Self? {
    guard valid, var resized = items.first(where: { $0.id == id }) else { return nil }
    resized.w = w
    resized.h = h
    guard resized.valid else { return nil }
    var placed = [resized]
    for var item in items.filter({ $0.id != id }).sorted(by: {
      $0.y == $1.y ? items.firstIndex(of: $0)! < items.firstIndex(of: $1)! : $0.y < $1.y
    }) {
      if item.y < resized.y {
        if item.overlaps(resized) { return nil }
      } else {
        item.y = max(
          item.y,
          placed.filter { item.x < $0.x + $0.w && item.x + item.w > $0.x }.map { $0.y + $0.h }.max()
            ?? item.y)
      }
      guard item.valid else { return nil }
      placed.append(item)
    }
    let byID = Dictionary(uniqueKeysWithValues: placed.map { ($0.id, $0) })
    let result = Self(items: items.compactMap { byID[$0.id] })
    return result.valid ? result : nil
  }
  func adding(_ item: CanvasItem) -> Self? {
    guard items.count < 48 else { return nil }
    for y in 0...24 - item.h {
      for x in 0...6 - item.w {
        var i = item
        i.x = x
        i.y = y
        let next = Self(items: items + [i])
        if next.valid { return next }
      }
    }
    return nil
  }
  func splitting(_ id: String) -> Self? {
    guard let item = items.first(where: { $0.id == id }), !item.kind.keys.isEmpty else {
      return nil
    }
    let keys = item.kind.keys
    let length = item.kind.vertical ? item.h : item.w
    let parts = keys.enumerated().map { n, key -> CanvasItem in
      let start = length * n / keys.count
      let end = length * (n + 1) / keys.count
      return CanvasItem(
        id: n == 0 ? item.id : UUID().uuidString, kind: .key,
        x: item.x + (item.kind.vertical ? 0 : start), y: item.y + (item.kind.vertical ? start : 0),
        w: item.kind.vertical ? item.w : end - start, h: item.kind.vertical ? end - start : item.h,
        command: key)
    }
    let result = Self(items: items.flatMap { $0.id == id ? parts : [$0] })
    return result.valid ? result : nil
  }
  func joining(_ id: String) -> Self? {
    for kind in [CanvasKind.volume, .channels, .navigation, .playback] {
      for first in items.filter({ $0.kind == .key && $0.command == kind.keys.first }) {
        var group = [first]
        for key in kind.keys.dropFirst() {
          let last = group.last!
          if let next = items.first(where: {
            $0.kind == .key && $0.command == key
              && (kind.vertical
                ? $0.x == last.x && $0.w == last.w && $0.y == last.y + last.h
                : $0.y == last.y && $0.h == last.h && $0.x == last.x + last.w)
          }) {
            group.append(next)
          }
        }
        if group.count == kind.keys.count && group.contains(where: { $0.id == id }) {
          var joined = first
          joined.kind = kind
          joined.command = nil
          joined.w = kind.vertical ? first.w : group.reduce(0) { $0 + $1.w }
          joined.h = kind.vertical ? group.reduce(0) { $0 + $1.h } : first.h
          let ids = Set(group.map(\.id))
          let result = Self(
            items: items.compactMap { $0.id == first.id ? joined : ids.contains($0.id) ? nil : $0 })
          if result.valid { return result }
        }
      }
    }
    return nil
  }
}
enum HandPreference: String, Codable, CaseIterable { case right, left }
enum CanvasTemplate: String, CaseIterable, Identifiable {
  case classic, liveTV, streaming, touchpad, minimal, bigButtons, full, numbersTouch
  var id: String { rawValue }
  var title: String {
    switch self {
    case .classic: L10n.text("Classic")
    case .liveTV: L10n.text("Live TV")
    case .streaming: L10n.text("Streaming")
    case .touchpad: L10n.text("Touchpad")
    case .minimal: L10n.text("Minimal")
    case .bigButtons: L10n.text("Big buttons")
    case .full: L10n.text("Full remote")
    case .numbersTouch: L10n.text("Numbers & touch")
    }
  }
  var detail: String {
    switch self {
    case .classic: L10n.text("Balanced everyday remote")
    case .liveTV: L10n.text("Numbers, channels and guide")
    case .streaming: L10n.text("Apps, navigation and playback")
    case .touchpad: L10n.text("A generous swipe surface")
    case .minimal: L10n.text("Navigation, volume and home")
    case .bigButtons: L10n.text("Large targets, fewer controls")
    case .full: L10n.text("The All controls arrangement")
    case .numbersTouch: L10n.text("Keypad beside the touchpad")
    }
  }
  // Keep generated geometry in sync with Android CanvasTemplate and shared/canvas-template-fixtures.json.
  func create(_ caps: Capabilities, apps: [AppShortcut], hand: HandPreference) -> RemoteCanvas {
    var items = [CanvasItem]()
    func add(
      _ k: CanvasKind, _ x: Int, _ y: Int, _ w: Int? = nil, _ h: Int? = nil,
      _ c: RemoteCommand? = nil, _ app: String? = nil
    ) {
      let i = CanvasItem(kind: k, x: x, y: y, w: w, h: h, command: c, app: app)
      if i.supported(caps, apps) { items.append(i) }
    }
    func row(_ keys: [RemoteCommand], _ y: Int) {
      let w = 6 / keys.count
      for (n, c) in keys.enumerated() { add(.key, n * w, y, w, 1, c) }
    }
    func group(_ k: CanvasKind, _ x: Int, _ y: Int, _ w: Int? = nil) {
      if Set(k.keys).isSubset(of: caps.commands) {
        add(k, x, y, w)
      } else {
        let width = w ?? k.minW
        for (n, c) in k.keys.enumerated() {
          add(
            .key, x + (k.vertical ? 0 : width * n / k.keys.count), y + (k.vertical ? n : 0),
            k.vertical ? width : width / k.keys.count, 1, c)
        }
      }
    }
    func pad(_ y: Int, _ touch: Bool = false, _ channels: Bool = true) {
      add(touch ? .touch : .dpad, 1, y, 4, 4)
      let v = hand == .right ? 0 : 5
      group(.volume, v, y + 1)
      add(.key, v, y + 3, 1, 1, .mute)
      if channels { group(.channels, 5 - v, y + 1) }
    }
    switch self {
    case .classic, .streaming, .touchpad:
      let start = caps.apps && !apps.isEmpty ? 1 : 0
      if start == 1 {
        for (n, a) in apps.prefix(2).enumerated() { add(.app, n * 3, 0, 3, 1, nil, a.target) }
      }
      pad(start, self == .touchpad, self != .streaming)
      group(.navigation, 0, start + 4, 4)
      if self == .streaming { add(.key, 4, start + 4, 2, 1, .apps) } else { add(.tv, 4, start + 4) }
      group(.playback, 0, start + 5, 6)
      add(.keyboard, 0, start + 6, 3)
      add(.key, 3, start + 6, 3, 1, self == .streaming ? .menu : .input)
    case .minimal:
      pad(0, false, false)
      group(.navigation, 0, 4, 6)
    case .bigButtons:
      add(.dpad, 0, 0, 6, 6)
      group(.navigation, 0, 6, 6)
      row([.volumeDown, .mute, .volumeUp], 7)
      group(.playback, 0, 8, 6)
    case .liveTV:
      add(.numbers, 0, 0, 6, 4)
      row([.guide, .info, .input], 4)
      pad(5)
      group(.navigation, 0, 9, 4)
      add(.tv, 4, 9)
      group(.playback, 0, 10, 6)
    case .numbersTouch:
      add(.numbers, 0, 0)
      add(.touch, 3, 0, 3, 4)
      group(.navigation, 0, 4, 4)
      add(.tv, 4, 4)
      row([.volumeDown, .mute, .volumeUp], 5)
      group(.playback, 0, 6, 6)
    case .full:
      row([.input, .settings, .apps], 0)
      for y in 0..<3 { row(Array(RemoteCommand.digits[(1 + y * 3)...(3 + y * 3)]), y + 1) }
      add(.key, 0, 4, 2, 1, .delete)
      add(.key, 2, 4, 2, 1, .zero)
      add(.tv, 4, 4)
      pad(5)
      items.removeAll { $0.command == .mute }
      row([.back, .home, .menu], 9)
      group(.playback, 0, 10, 6)
      row([.mute, .guide, .info], 11)
      add(.keyboard, 0, 12, 6)
    }
    return RemoteCanvas(items: items).removingEmptyRows()
  }
}
struct SavedPreset: Identifiable, Codable, Equatable {
  var id = UUID().uuidString
  var name: String
  var canvas: RemoteCanvas
  var apps: [AppShortcut]
}

/// Runtime fitting changes presentation only; saved grid positions and spans stay intact.
enum CanvasFit {
  static func rowHeight(cell: Double, available: Double, rows: Int, gap: Double) -> Double {
    let count = Double(max(1, rows))
    let natural = min(72, max(48, cell))
    return max(48, min(natural, (available - gap * (count - 1)) / count))
  }
}
