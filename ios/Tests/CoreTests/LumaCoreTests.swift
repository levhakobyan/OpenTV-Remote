import XCTest

@testable import LumaCore

final class LumaCoreTests: XCTestCase {
  func testFittingCapsSparseLayoutsAndKeepsDenseLayoutsScrollable() {
    XCTAssertEqual(CanvasFit.rowHeight(cell: 56, available: 800, rows: 1, gap: 4), 56)
    XCTAssertEqual(CanvasFit.rowHeight(cell: 140, available: 1000, rows: 2, gap: 4), 72)
    XCTAssertEqual(
      CanvasFit.rowHeight(cell: 60, available: 800, rows: 15, gap: 4), 49.6, accuracy: 0.001)
    XCTAssertEqual(CanvasFit.rowHeight(cell: 60, available: 300, rows: 15, gap: 4), 48)
  }
  func testRuntimeFittingPreservesCustomGrid() throws {
    let custom = RemoteCanvas(items: [CanvasItem(kind: .key, x: 1, y: 2, w: 3, command: .home)])
    let saved = try JSONEncoder().encode(custom)
    _ = CanvasFit.rowHeight(cell: 56, available: 800, rows: custom.rows, gap: 8)
    _ = CanvasFit.rowHeight(cell: 110, available: 350, rows: custom.rows, gap: 8)
    XCTAssertEqual(custom, try JSONDecoder().decode(RemoteCanvas.self, from: saved))
  }

  let full = Capabilities(
    commands: Set(RemoteCommand.allCases), text: true, pointer: true, apps: true, scroll: true)
  /// Compare runtime geometry with the same canonical fixture exercised by Kotlin.
  func testBuiltInTemplatesMatchSharedCrossPlatformFixtures() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
      .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let fixture = try JSONDecoder().decode(
      CanvasFixture.self,
      from: Data(contentsOf: root.appendingPathComponent("shared/canvas-template-fixtures.json")))
    XCTAssertEqual(fixture.version, 1)
    XCTAssertEqual(CanvasTemplate.allCases.map(\.rawValue), fixture.templates.map(\.ios))
    for definition in fixture.templates {
      let template = try XCTUnwrap(CanvasTemplate(rawValue: definition.ios))
      XCTAssertEqual(template.title, definition.title)
      XCTAssertEqual(template.detail, definition.detail)
    }
    let apps = fixture.apps.map { AppShortcut(name: $0.name, target: $0.target) }
    var actual = [String: [String]]()
    for profile in fixture.profiles {
      let codes = Set(profile.commands)
      let caps = Capabilities(
        commands: Set(RemoteCommand.allCases.filter { codes.contains($0.androidKey) }),
        text: profile.text, pointer: profile.pointer, apps: profile.apps, scroll: profile.scroll)
      XCTAssertEqual(codes, Set(caps.commands.map(\.androidKey)))
      XCTAssertEqual(codes.count, caps.commands.count)
      if profile.id == "android622" { XCTAssertEqual(caps, Capabilities.android(622)) }
      for count in 0...2 {
        for hand in HandPreference.allCases {
          for template in CanvasTemplate.allCases {
            let selectedApps = Array(apps.prefix(count))
            let canvas = template.create(caps, apps: selectedApps, hand: hand)
            XCTAssertTrue(canvas.valid)
            XCTAssertTrue(canvas.items.allSatisfy { $0.supported(caps, selectedApps) })
            let items = canvas.items.sorted {
              if $0.y != $1.y { return $0.y < $1.y }
              if $0.x != $1.x { return $0.x < $1.x }
              return $0.kind.rawValue < $1.kind.rawValue
            }.map { item in
              [
                item.kind.rawValue, String(item.x), String(item.y), String(item.w), String(item.h),
                item.command.map { String($0.androidKey) } ?? "-", item.app ?? "-",
              ]
              .joined(separator: "|")
            }
            actual["\(profile.id)/apps\(count)/\(hand.rawValue)/\(template.rawValue)"] =
              ["rows=\(canvas.rows)"] + items
          }
        }
      }
    }
    if let path = ProcessInfo.processInfo.environment["LUMA_CANVAS_SNAPSHOT_OUTPUT"] {
      let encoder = JSONEncoder()
      encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
      try encoder.encode(CanvasSnapshot(cases: actual)).write(to: URL(fileURLWithPath: path))
    }
    let missing = Set(actual.keys).subtracting(fixture.cases.keys)
    let extra = Set(fixture.cases.keys).subtracting(actual.keys)
    XCTAssertTrue(
      missing.isEmpty && extra.isEmpty,
      "Fixture coverage: \(missing.count) missing and \(extra.count) extra cases")
    guard missing.isEmpty && extra.isEmpty else { return }
    for (key, items) in actual { XCTAssertEqual(items, fixture.cases[key], key) }
  }
  func testFullRemoteUsesActualDigitsAndHandednessOnlyMovesRails() throws {
    let right = CanvasTemplate.full.create(full, apps: [], hand: .right)
    let left = CanvasTemplate.full.create(full, apps: [], hand: .left)
    XCTAssertEqual(right.items.first { $0.kind == .volume }?.x, 0)
    XCTAssertEqual(left.items.first { $0.kind == .volume }?.x, 5)
    for digit in RemoteCommand.digits {
      let a = try XCTUnwrap(right.items.first { $0.command == digit })
      let b = try XCTUnwrap(left.items.first { $0.command == digit })
      XCTAssertEqual(a.kind, .key)
      XCTAssertEqual([a.x, a.y, a.w, a.h], [b.x, b.y, b.w, b.h])
    }
    XCTAssertEqual(right.items.filter { $0.command == .mute }.count, 1)
    let saved = SavedPreset(name: "Моя TV", canvas: right, apps: [])
    XCTAssertEqual(
      try JSONDecoder().decode(SavedPreset.self, from: JSONEncoder().encode(saved)), saved)
    XCTAssertEqual(CanvasTemplate.full.rawValue, "full")
    XCTAssertEqual(HandPreference.left.rawValue, "left")
  }
  func testAllTemplatesAndHandsStayWithinGrid() {
    for caps in [
      full, Capabilities.android(622), Capabilities(commands: [.home, .back, .volumeUp]),
      Capabilities(pointer: true), Capabilities(),
    ] {
      for hand in HandPreference.allCases {
        for template in CanvasTemplate.allCases {
          let canvas = template.create(caps, apps: Array(AppShortcut.catalog.prefix(2)), hand: hand)
          XCTAssertTrue(canvas.valid, "\(template) \(hand)")
          XCTAssertTrue(
            canvas.items.allSatisfy { $0.supported(caps, Array(AppShortcut.catalog.prefix(2))) })
        }
      }
    }
  }
  func testResizePushesCascadeAtomically() {
    let a = CanvasItem(kind: .key, x: 0, y: 0, w: 2, command: .menu)
    let b = CanvasItem(kind: .navigation, x: 0, y: 1, w: 4)
    let c = CanvasItem(kind: .key, x: 2, y: 2, w: 2, command: .info)
    let canvas = RemoteCanvas(items: [a, b, c])
    let grown = canvas.resizing(a.id, w: 2, h: 3)!
    XCTAssertEqual(grown.items[1].y, 3)
    XCTAssertEqual(grown.items[2].y, 4)
    XCTAssertEqual(grown.items.map(\.id), canvas.items.map(\.id))
    XCTAssertNil(canvas.resizing(a.id, w: 2, h: 24))
    XCTAssertEqual(canvas.items[1].y, 1)
  }
  func testSplitAndContextualJoin() {
    let item = CanvasItem(kind: .channels, x: 5, y: 1, h: 4)
    let canvas = RemoteCanvas(items: [item])
    let split = canvas.splitting(item.id)!
    XCTAssertEqual(split.items.count, 2)
    XCTAssertEqual(split.items[0].h, 2)
    XCTAssertEqual(split.joining(split.items[1].id), canvas)
  }
  func testSwapAndOverlapRejection() {
    let a = CanvasItem(kind: .key, x: 0, y: 0, command: .home)
    let b = CanvasItem(kind: .key, x: 2, y: 0, command: .menu)
    let canvas = RemoteCanvas(items: [a, b])
    XCTAssertEqual(canvas.placing(a.id, x: 2, y: 0)?.items[1].x, 0)
    XCTAssertNil(canvas.placing(a.id, x: 6, y: 0))
  }
  func testRowsPreserveGapsUntilExplicitCompaction() {
    let canvas = RemoteCanvas(items: [CanvasItem(kind: .key, x: 1, y: 5, command: .home)])
    XCTAssertEqual(canvas.rows, 6)
    XCTAssertEqual(canvas.emptyRows, 5)
    XCTAssertEqual(canvas.removingEmptyRows().items[0].y, 0)
  }
  func testCanvasCodablePreservesGeometry() throws {
    let c = CanvasTemplate.full.create(full, apps: [], hand: .left)
    XCTAssertEqual(try JSONDecoder().decode(RemoteCanvas.self, from: JSONEncoder().encode(c)), c)
  }
  func testColorsAndContrast() {
    for r in stride(from: 0, through: 255, by: 17) {
      for g in stride(from: 0, through: 255, by: 17) {
        for b in stride(from: 0, through: 255, by: 17) {
          let p = AccentPalette(String(format: "%02X%02X%02X", r, g, b))
          XCTAssertGreaterThanOrEqual(AccentPalette.contrast(p.accent, p.raised), 4.5)
        }
      }
    }
    XCTAssertNil(AccentPalette.normalize("#123456FF"))
    XCTAssertEqual(AccentPalette.normalize("#abcdef"), "ABCDEF")
  }
  func testShortcutBoundaryAndCatalog() {
    XCTAssertTrue(AppShortcut.catalog.allSatisfy(\.valid))
    XCTAssertFalse(
      AppShortcut(name: "Unsafe", target: "javascript:alert(1)", kind: .deepLink).valid)
    XCTAssertFalse(AppShortcut(name: "Unsafe", target: "https://user:pass@example.com").valid)
    XCTAssertTrue(AppShortcut(name: "Kodi", target: "org.xbmc.kodi", kind: .package).valid)
  }
  func testAddressPolicy() {
    for host in ["192.168.1.2", "10.0.2.2", "172.16.0.1", "169.254.1.2"] {
      XCTAssertTrue(LocalAddress.valid(host))
    }
    for host in [
      "127.0.0.1", "8.8.8.8", "192.168.1.999", "10.1.2", "https://10.0.0.1", "10.0.0.1.evil",
    ] { XCTAssertFalse(LocalAddress.valid(host)) }
  }
  func testRetiredTVMigrationKeepsEveryNativeEntryAndLayoutKey() throws {
    let entries: [Any] = [
      ["id": "android:one", "name": "Living room", "host": "192.168.1.2", "platform": "androidTV"],
      [
        "id": "retired-cursor", "name": "Old pointer", "host": "192.168.1.2",
        "platform": "companion",
      ],
      ["id": "future", "platform": "unsupported-future-platform"], "malformed entry",
      ["id": "samsung:two", "name": "Bedroom", "host": "192.168.1.3", "platform": "samsung"],
      ["id": "webos:three", "name": "Office", "host": "192.168.1.4", "platform": "webOS"],
    ]
    let migrated = SavedTVMigration.decode(try JSONSerialization.data(withJSONObject: entries))
    XCTAssertEqual(migrated.devices.map(\.id), ["android:one", "samsung:two", "webos:three"])
    XCTAssertEqual(migrated.devices.map(\.name), ["Living room", "Bedroom", "Office"])
    XCTAssertEqual(migrated.removedIDs, ["retired-cursor"])
    XCTAssertEqual(
      migrated.retiredProfileIDs([
        "android:one", "samsung:two", "retired-cursor", "companion:orphan",
      ]),
      ["retired-cursor", "companion:orphan"])
    XCTAssertFalse(migrated.isRetired("android:one"))
    XCTAssertTrue(migrated.isRetired("retired-cursor"))
    XCTAssertTrue(migrated.isRetired("companion:orphan"))
    let again = SavedTVMigration.decode(try JSONEncoder().encode(migrated.devices))
    XCTAssertEqual(again.devices, migrated.devices)
    XCTAssertTrue(again.removedIDs.isEmpty)
    XCTAssertTrue(SavedTVMigration.isRetiredCredential("companion-pin:retired-cursor"))
    XCTAssertTrue(SavedTVMigration.isRetiredCredential("companion-token:orphan"))
    for native in ["android-pin:one", "samsung-pin:two", "samsung-token:two", "lg:three"] {
      XCTAssertFalse(SavedTVMigration.isRetiredCredential(native))
    }
  }
  func testAppLanguagePersistsAndSystemResolvesSupportedLanguage() throws {
    let name = "com.luma.core.language-test." + UUID().uuidString
    let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
    defer { defaults.removePersistentDomain(forName: name) }
    XCTAssertEqual(AppLanguage.load(from: defaults), .system)
    AppLanguage.russian.save(to: defaults)
    XCTAssertEqual(AppLanguage.load(from: try XCTUnwrap(UserDefaults(suiteName: name))), .russian)
    AppLanguage.english.save(to: defaults)
    XCTAssertEqual(AppLanguage.load(from: defaults), .english)
    AppLanguage.system.save(to: defaults)
    XCTAssertEqual(AppLanguage.load(from: defaults), .system)
    defaults.set("invalid", forKey: AppLanguage.defaultsKey)
    XCTAssertEqual(AppLanguage.load(from: defaults), .system)
    XCTAssertEqual(AppLanguage.system.resolvedCode(preferredLanguages: ["ru-RU"]), "ru")
    XCTAssertEqual(AppLanguage.system.resolvedCode(preferredLanguages: ["en-GB"]), "en")
    XCTAssertEqual(AppLanguage.system.resolvedCode(preferredLanguages: ["de-DE"]), "en")
    XCTAssertEqual(AppLanguage.english.resolvedCode(preferredLanguages: ["ru"]), "en")
    XCTAssertEqual(AppLanguage.russian.resolvedCode(preferredLanguages: ["en"]), "ru")
  }
  func testLanguageBundleLookupChangesImmediatelyWithoutTranslatingUserContent() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
      .deletingLastPathComponent().deletingLastPathComponent()
    let resources = try XCTUnwrap(Bundle(path: root.appendingPathComponent("LumaRemote").path))
    XCTAssertEqual(L10n.text("Find my TV", language: .english, resources: resources), "Find my TV")
    XCTAssertEqual(
      L10n.text("Find my TV", language: .russian, resources: resources), "Найти телевизор")
    XCTAssertEqual(L10n.text("Language", language: .russian, resources: resources), "Язык")
    XCTAssertEqual(L10n.text("Language", language: .english, resources: resources), "Language")
    XCTAssertEqual(
      L10n.text("User-created TV name", language: .russian, resources: resources),
      "User-created TV name")
  }
  func testPressMovingOutsideCannotRestartUntilLift() {
    var contact = RemotePressContact()
    XCTAssertTrue(contact.remainsInside(x: 25, y: 25, width: 50, height: 50))
    XCTAssertFalse(contact.remainsInside(x: 51, y: 25, width: 50, height: 50))
    XCTAssertTrue(contact.canceled)
    XCTAssertFalse(contact.remainsInside(x: 25, y: 25, width: 50, height: 50))
    contact.reset()
    XCTAssertTrue(contact.remainsInside(x: 25, y: 25, width: 50, height: 50))
    XCTAssertFalse(contact.remainsInside(x: -.infinity, y: 0, width: 50, height: 50))
  }
  func testPhysicalHoldPolicyDoesNotRepeatOKOrToggleButtons() throws {
    XCTAssertTrue(RemoteCommand.ok.androidNativeHold)
    XCTAssertFalse(RemoteCommand.ok.repeats)
    for key in [RemoteCommand.up, .down, .left, .right] {
      XCTAssertTrue(key.androidNativeHold)
      XCTAssertTrue(key.repeats)
    }
    for key in [RemoteCommand.power, .home, .back, .mute, .playPause, .input, .menu] {
      XCTAssertFalse(key.repeats)
      XCTAssertFalse(key.androidNativeHold)
    }
    XCTAssertTrue(RemoteCommand.delete.repeats)
    XCTAssertEqual(Wire.key(.ok, direction: .down), Data([0x52, 4, 8, 23, 16, 1]))
    XCTAssertEqual(Wire.key(.ok, direction: .up), Data([0x52, 4, 8, 23, 16, 2]))
  }
  @MainActor func testHoldReleaseFinishesBeforeTheNextPress() async {
    let holds = RemoteHoldSequence()
    let began = expectation(description: "First key down")
    let cleaning = expectation(description: "First key release pending")
    var release: CheckedContinuation<Void, Never>?
    var events: [String] = []
    holds.start {
      events.append("down1")
      began.fulfill()
      do { try await Task.sleep(for: .seconds(3600)) } catch {}
      cleaning.fulfill()
      await withCheckedContinuation { release = $0 }
      events.append("up1")
    }
    await fulfillment(of: [began], timeout: 2)
    holds.stop()
    let next = holds.start { events.append("down2") }
    await fulfillment(of: [cleaning], timeout: 2)
    XCTAssertEqual(events, ["down1"], "A new hold must not overtake delayed key-up")
    release?.resume()
    await next.value
    XCTAssertEqual(events, ["down1", "up1", "down2"])
  }
  @MainActor func testCanceledQueuedHoldNeverSendsDown() async {
    let holds = RemoteHoldSequence()
    let began = expectation(description: "First press")
    let cleaning = expectation(description: "Cleanup entered")
    var release: CheckedContinuation<Void, Never>?
    var sent = [String]()
    holds.start {
      sent.append("down1")
      began.fulfill()
      do { try await Task.sleep(for: .seconds(3600)) } catch {}
      cleaning.fulfill()
      await withCheckedContinuation { release = $0 }
      sent.append("up1")
    }
    await fulfillment(of: [began], timeout: 2)
    let canceled = holds.start { sent.append("must-not-send") }
    holds.stop()
    await fulfillment(of: [cleaning], timeout: 2)
    release?.resume()
    await canceled.value
    XCTAssertEqual(sent, ["down1", "up1"])
  }
  @MainActor func testCanceledMiddleHoldStillWaitsForEarlierRelease() async {
    let holds = RemoteHoldSequence()
    let began = expectation(description: "First hold")
    let cleaning = expectation(description: "First release pending")
    var release: CheckedContinuation<Void, Never>?
    var events = [String]()
    holds.start {
      events.append("down1")
      began.fulfill()
      do { try await Task.sleep(for: .seconds(3600)) } catch {}
      cleaning.fulfill()
      await withCheckedContinuation { release = $0 }
      events.append("up1")
    }
    await fulfillment(of: [began], timeout: 2)
    holds.start { events.append("must-not-send") }
    holds.stop()
    let third = holds.start { events.append("down3") }
    await fulfillment(of: [cleaning], timeout: 2)
    XCTAssertEqual(events, ["down1"])
    release?.resume()
    await third.value
    XCTAssertEqual(events, ["down1", "up1", "down3"])
  }
  func testWireBoundariesAndExactHome() throws {
    XCTAssertEqual(try Wire.parse(Wire.key(.right, direction: .down)).nested(10).number(2), 1)
    XCTAssertEqual(try Wire.parse(Wire.key(.right, direction: .up)).nested(10).number(2), 2)
    XCTAssertEqual(Wire.key(.home), Data([0x52, 0x04, 0x08, 0x03, 0x10, 0x03]))
    XCTAssertEqual(try Wire.parse(Wire.string(4, "բարև")).first?.bytes, Data("բարև".utf8))
    XCTAssertThrowsError(try Wire.parse(Data([0x12, 0xff, 0xff, 0xff, 0x7f])))
    XCTAssertThrowsError(try Wire.parse(Data(repeating: 0xff, count: 10)))
    XCTAssertThrowsError(try Wire.parse(Data([0])))
  }
  func testAndroidIMEDefaultsAndUpdatesPreserveTVFormat() throws {
    var ime = AndroidIMEState()
    XCTAssertEqual(try Wire.parse(ime.insertion("hello")).nested(21).number(1), 0)
    try ime.receive(Wire.parse(Wire.bytes(20, Wire.bytes(1, Wire.integer(1, 9)))))
    XCTAssertEqual(try Wire.parse(ime.insertion("hello")).nested(21).number(1), 0)
    try ime.receive(Wire.parse(Wire.bytes(21, Wire.integer(1, 7) + Wire.integer(2, 42))))
    let message = try Wire.parse(ime.insertion("բարև🙂")).nested(21)
    XCTAssertEqual(message.number(1), 7)
    XCTAssertEqual(message.number(2), 42)
    let edit = try message.nested(3)
    XCTAssertEqual(edit.number(1), 1)
    let text = try edit.nested(2)
    XCTAssertEqual(text.number(1), "բարև🙂".utf16.count - 1)
    XCTAssertEqual(text.first { $0.number == 3 }?.bytes, Data("բարև🙂".utf8))
    XCTAssertThrowsError(try ime.insertion(""))
    XCTAssertThrowsError(try ime.insertion(String(repeating: "a", count: 1001)))
    // A new field may update its counter without invalidating the TV's IME counter.
    try ime.receive(Wire.parse(Wire.bytes(22, Wire.bytes(2, Wire.integer(1, 43)))))
    XCTAssertEqual(try Wire.parse(ime.insertion("next")).nested(21).number(2), 43)
    ime = AndroidIMEState()
    XCTAssertEqual(try Wire.parse(ime.insertion("new connection")).nested(21).number(2), 0)
    try ime.receive(Wire.parse(Wire.bytes(21, Data())))
    XCTAssertNoThrow(try ime.insertion("zero counters explicitly supplied"))
  }
  func testCapabilitiesAreNegotiated() {
    XCTAssertFalse(Capabilities.android(2).text)
    XCTAssertFalse(Capabilities.android(2).commands.contains(.power))
    XCTAssertTrue(Capabilities.android(622).apps)
    XCTAssertFalse(Capabilities.android(0).navigation)
  }
  func testSamsungMetadataAndCommands() throws {
    let raw = Data(
      #"{"type":"Samsung SmartTV","name":"Living room","device":{"id":"uuid:tv"},"isSupport":{"remote_fourDirections":"false","ImeSyncedSupport":"true"}}"#
        .utf8)
    let (tv, c) = try SamsungCodec.info(raw, host: "192.168.1.2")
    XCTAssertEqual(tv.id, "samsung:uuid:tv")
    XCTAssertFalse(c.navigation)
    XCTAssertTrue(c.text)
    XCTAssertFalse(c.apps)
    XCTAssertThrowsError(try SamsungCodec.key(.playPause))
    XCTAssertTrue(try SamsungCodec.key(.home).contains("KEY_HOME"))
    XCTAssertTrue(
      try SamsungCodec.text("բարև")[1].contains(Data("բարև".utf8).base64EncodedString()))
    XCTAssertFalse(SamsungCodec.validToken("bad\n"))
    XCTAssertThrowsError(try SamsungCodec.info(Data("{}".utf8), host: "192.168.1.2"))
  }
  func testCertificateIntegerPadding() {
    XCTAssertEqual(EncodingRules.positiveInteger(Data([0, 0, 0x7f])), Data([0x7f]))
    XCTAssertEqual(EncodingRules.positiveInteger(Data([0x80, 1])), Data([0, 0x80, 1]))
    XCTAssertEqual(EncodingRules.positiveInteger(Data([0, 0])), Data([0]))
    for byte in 1...255 {
      let value = EncodingRules.positiveInteger(Data([UInt8(byte)]))
      XCTAssertEqual(value.count, byte < 128 ? 1 : 2)
    }
  }
}

private struct CanvasSnapshot: Codable { let cases: [String: [String]] }
private struct CanvasFixture: Decodable {
  struct Template: Decodable { let ios, title, detail: String }
  struct Profile: Decodable {
    let id: String
    let commands: [Int]
    let text, pointer, apps, scroll: Bool
  }
  struct App: Decodable { let name, target: String }
  let version: Int
  let templates: [Template]
  let profiles: [Profile]
  let apps: [App]
  let cases: [String: [String]]
}
