import UIKit
import XCTest

final class RemoteFlowTests: XCTestCase {
  @MainActor func testLiveIMEBidirectionalEditsAndFallback() async throws {
    continueAfterFailure = false
    let app = try await connectAndroidFixture()
    try await imeAction(
      "open",
      [
        "text": "ab", "start": 2, "end": 2, "label": "Search", "ime": 7, "revision": 42,
        "context": 7,
      ])
    XCTAssertTrue(app.staticTexts["Live keyboard"].waitForExistence(timeout: 5))
    let field = app.textViews["tv-text-input"]
    XCTAssertTrue(field.waitForExistence(timeout: 4))
    XCTAssertEqual(field.value as? String, "ab")
    field.typeText("c")
    try await waitForIMEText("abc")
    XCTAssertFalse(app.buttons["Send text"].exists)
    let enterBefore = try await imeState()
    try await imeAction("ack-pause")
    field.typeText("d\n")
    let queuedEnter = try await imeState()
    XCTAssertEqual(queuedEnter.text, "abcd")
    XCTAssertEqual(queuedEnter.keyCounts["66", default: 0], enterBefore.keyCounts["66", default: 0])
    try await imeAction("ack-resume")
    try await waitForIMEKey(66, count: enterBefore.keyCounts["66", default: 0] + 1)
    try await imeAction("remote", ["text": "բարև🙂 Привет", "start": 4, "end": 4])
    let reflected = expectation(
      for: NSPredicate(format: "value == %@", "բարև🙂 Привет"), evaluatedWith: field)
    await fulfillment(of: [reflected], timeout: 4)
    field.typeText("X")
    try await waitForIMEText("բարևX🙂 Привет")
    // A native paste exercises Unicode without relying on the Simulator keyboard layout.
    try await imeAction("remote", ["text": "", "start": 0, "end": 0])
    let empty = expectation(for: NSPredicate(format: "value == %@", ""), evaluatedWith: field)
    await fulfillment(of: [empty], timeout: 4)
    UIPasteboard.general.string = "Հայերեն русский 🙂"
    field.press(forDuration: 1)
    let paste = app.buttons["Paste"].firstMatch
    if paste.waitForExistence(timeout: 3) { paste.tap() } else { app.menuItems["Paste"].tap() }
    try await waitForIMEText("Հայերեն русский 🙂")
    capture(app, "iPhone live IME Unicode sync")
    for _ in 0..<4 {
      if app.buttons["Clear text"].isHittable { break }
      app.swipeUp()
    }
    app.buttons["Clear text"].tap()
    try await waitForIMEText("")
    try await imeAction(
      "open",
      [
        "text": "hold", "start": 4, "end": 4, "label": "Search", "ime": 7, "revision": 100,
        "context": 8,
      ])
    XCTAssertTrue(app.staticTexts["Live keyboard"].waitForExistence(timeout: 4))
    try await imeAction("ack-pause")
    let before = try await imeState()
    field.typeText("XY")
    try await Task.sleep(for: .milliseconds(400))
    let paused = try await imeState()
    XCTAssertEqual(paused.editCount, before.editCount + 1)
    field.typeText("\n")
    XCTAssertTrue(app.staticTexts["Manual Send"].waitForExistence(timeout: 4))
    XCTAssertTrue(app.buttons["Review unsent phone text"].exists)
    try await imeAction("ack-resume")
    try await Task.sleep(for: .milliseconds(500))
    let stopped = try await imeState()
    XCTAssertEqual(stopped.editCount, paused.editCount)
    XCTAssertEqual(stopped.keyCounts["66", default: 0], paused.keyCounts["66", default: 0])
    XCTAssertTrue(app.staticTexts["Manual Send"].exists)
    try await imeAction(
      "open",
      [
        "text": "old", "start": 3, "end": 3, "label": "Search", "ime": 7, "revision": 300,
        "context": 10,
      ])
    XCTAssertTrue(app.staticTexts["Live keyboard"].waitForExistence(timeout: 4))
    try await imeAction("ack-pause")
    field.typeText("AB")
    field.typeText("\n")
    try await Task.sleep(for: .milliseconds(200))
    let oldField = try await imeState()
    try await imeAction(
      "field",
      [
        "text": "fresh", "start": 5, "end": 5, "label": "Search", "ime": 7, "revision": 400,
        "context": 11,
      ])
    try await imeAction("ack-resume")
    try await Task.sleep(for: .milliseconds(500))
    let newField = try await imeState()
    XCTAssertEqual(newField.text, "fresh")
    XCTAssertEqual(newField.editCount, oldField.editCount)
    XCTAssertEqual(newField.keyCounts["66", default: 0], oldField.keyCounts["66", default: 0])
    XCTAssertTrue(app.staticTexts["Manual Send"].waitForExistence(timeout: 4))
    XCTAssertTrue(app.buttons["Review unsent phone text"].exists)
    try await imeAction(
      "open",
      [
        "text": "••••x", "start": 5, "end": 5, "label": "", "ime": 7, "revision": 500,
        "context": 12,
      ])
    XCTAssertTrue(app.staticTexts["Manual Send"].waitForExistence(timeout: 4))
    XCTAssertFalse(app.switches["ime-private-input"].isEnabled)
    XCTAssertFalse((field.value as? String ?? "").contains("••••x"))
    field.typeText("Z")
    for _ in 0..<4 {
      if app.buttons["Send text"].isHittable { break }
      app.swipeUp()
    }
    app.buttons["Send text"].tap()
    try await waitForIMEText("••••xZ")
    XCTAssertFalse(
      (field.value as? String ?? "").isEmpty,
      "Manual Send must retain its transient draft until explicit clear or dismissal")
    capture(app, "iPhone live IME private fallback")
    try await imeAction("close")
    XCTAssertTrue(app.buttons["Remote menu"].waitForExistence(timeout: 4))
    app.buttons["Remote menu"].tap()
    app.buttons["About OpenTV-Remote"].tap()
    let automatic = app.switches["Open on TV text focus"]
    XCTAssertEqual(automatic.value as? String, "1")
    automatic.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
    XCTAssertEqual(automatic.value as? String, "0")
    app.buttons["Done"].firstMatch.tap()
    try await imeAction(
      "open",
      [
        "text": "manual opening", "start": 14, "end": 14, "label": "Search", "ime": 7,
        "revision": 600, "context": 13,
      ])
    try await Task.sleep(for: .milliseconds(600))
    XCTAssertFalse(app.navigationBars["TV keyboard"].exists)
    app.buttons["Remote menu"].tap()
    app.collectionViews.buttons["Keyboard"].tap()
    XCTAssertTrue(app.staticTexts["Live keyboard"].waitForExistence(timeout: 4))
    try await imeAction("close")
  }
  private struct IMEFixtureState: Decodable {
    let text: String
    let editCount: Int
    let keyCounts: [String: Int]
  }
  private func imeAction(_ name: String, _ body: [String: Any] = [:]) async throws {
    var request = URLRequest(url: URL(string: "http://127.0.0.1:8771/android/ime/live/" + name)!)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = try JSONSerialization.data(withJSONObject: body)
    let (_, response) = try await URLSession.shared.data(for: request)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
  }
  private func imeState() async throws -> IMEFixtureState {
    let (data, response) = try await URLSession.shared.data(
      from: URL(string: "http://127.0.0.1:8771/android/ime/state")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    return try JSONDecoder().decode(IMEFixtureState.self, from: data)
  }
  private func waitForIMEText(_ expected: String) async throws {
    for _ in 0..<40 {
      if try await imeState().text == expected { return }
      try await Task.sleep(for: .milliseconds(100))
    }
    let state = try await imeState()
    XCTAssertEqual(state.text, expected)
  }
  private func waitForIMEKey(_ key: Int, count: Int) async throws {
    for _ in 0..<40 {
      if try await imeState().keyCounts[String(key), default: 0] == count { return }
      try await Task.sleep(for: .milliseconds(100))
    }
    let state = try await imeState()
    XCTAssertEqual(state.keyCounts[String(key), default: 0], count)
  }
  @MainActor func testAndroidTVAndCustomization() async throws {
    continueAfterFailure = false
    let app = try await connectAndroidFixture()
    let baseline = try await counts()
    app.buttons["Home"].firstMatch.tap()
    app.buttons["Volume up"].firstMatch.press(forDuration: 0.8)
    let received = try await counts()
    XCTAssertGreaterThan(received["home"]!, baseline["home"]!)
    XCTAssertGreaterThan(received["volume"]!, baseline["volume"]! + 1)
    let released = received["volume"]!
    try await Task.sleep(for: .milliseconds(400))
    let stopped = try await counts()
    XCTAssertEqual(stopped["volume"], released)
    let beforeArrow = try await counts()
    app.buttons["Right"].firstMatch.press(forDuration: 0.6)
    var afterArrow = try await counts()
    for _ in 0..<20 {
      if afterArrow["rightUp"]! > beforeArrow["rightUp"]! { break }
      try await Task.sleep(for: .milliseconds(100))
      afterArrow = try await counts()
    }
    XCTAssertEqual(afterArrow["rightDown"]!, beforeArrow["rightDown"]! + 1)
    XCTAssertEqual(afterArrow["rightUp"]!, beforeArrow["rightUp"]! + 1)
    try await Task.sleep(for: .milliseconds(300))
    let arrowStopped = try await counts()
    XCTAssertEqual(arrowStopped["rightDown"], afterArrow["rightDown"])
    app.buttons["Right"].firstMatch.tap()
    var tapped = try await counts()
    for _ in 0..<20 {
      if tapped["rightUp"]! > afterArrow["rightUp"]! { break }
      try await Task.sleep(for: .milliseconds(100))
      tapped = try await counts()
    }
    XCTAssertEqual(tapped["rightDown"]!, afterArrow["rightDown"]! + 1)
    XCTAssertEqual(tapped["rightUp"]!, afterArrow["rightUp"]! + 1)
    capture(app, "iPhone classic remote")
    app.buttons["Remote menu"].tap()
    app.collectionViews.buttons["Keyboard"].tap()
    let textField = app.descendants(matching: .any)["tv-text-input"]
    XCTAssertTrue(textField.waitForExistence(timeout: 4))
    app.switches["ime-private-input"].tap()
    textField.tap()
    textField.typeText("OpenTV iPhone")
    app.buttons["Send text"].tap()
    let feedback = app.staticTexts["text-send-feedback"]
    XCTAssertTrue(feedback.waitForExistence(timeout: 3))
    XCTAssertTrue(feedback.label.hasPrefix("Sent to TV"))
    XCTAssertEqual(textField.value as? String, "OpenTV iPhone")
    try await action("/android/ime/open")
    try await Task.sleep(for: .milliseconds(1200))
    if app.staticTexts["Live keyboard"].exists {
      textField.typeText("OpenTV iPhone")
    } else {
      app.buttons["Send text"].tap()
      XCTAssertEqual(textField.value as? String, "OpenTV iPhone")
    }
    app.buttons["Done"].firstMatch.tap()
    let textReceived = try await counts()
    XCTAssertGreaterThan(textReceived["text"]!, baseline["text"]!)
    app.buttons["Customize remote"].tap()
    XCTAssertTrue(app.navigationBars["Customize remote"].waitForExistence(timeout: 4))
    app.buttons["Presets"].tap()
    XCTAssertTrue(app.navigationBars["Presets"].waitForExistence(timeout: 4))
    capture(app, "iPhone preset previews")
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Minimal")).firstMatch.tap()
    app.buttons["Use layout"].tap()
    let volume = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Volume rocker,"))
      .firstMatch
    XCTAssertTrue(volume.waitForExistence(timeout: 4))
    // Move a never-selected widget using a short touch, without waiting for long press.
    let initialVolume = app.buttons["Volume rocker, column 1, row 2"]
    let initialTop = initialVolume.frame.minY
    let rightCell = app.buttons["Grid column 6, row 2"]
    initialVolume.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).press(
      forDuration: 0.05,
      thenDragTo: rightCell.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
    XCTAssertTrue(
      app.buttons["Volume rocker, column 6, row 2"].waitForExistence(timeout: 3),
      "A control must drag directly without a prior selection tap")
    let rightVolume = app.buttons["Volume rocker, column 6, row 2"]
    rightVolume.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).press(
      forDuration: 0.05,
      thenDragTo: app.buttons["Grid column 1, row 2"].coordinate(
        withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
    XCTAssertTrue(app.buttons["Volume rocker, column 1, row 2"].waitForExistence(timeout: 3))
    XCTAssertEqual(
      app.buttons["Volume rocker, column 1, row 2"].frame.minY, initialTop, accuracy: 1,
      "Selection must not move the grid under the finger")
    // A partial overlap with the D-pad must reject the drop, leaving geometry intact.
    app.buttons["Volume rocker, column 1, row 2"].coordinate(
      withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)
    ).press(
      forDuration: 0.05,
      thenDragTo: app.buttons["Grid column 2, row 2"].coordinate(
        withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
    XCTAssertTrue(app.buttons["Volume rocker, column 1, row 2"].waitForExistence(timeout: 3))
    XCTAssertEqual(
      app.buttons["Volume rocker, column 1, row 2"].frame.minY, initialTop, accuracy: 1)
    volume.tap()
    XCTAssertTrue(app.buttons["Split"].waitForExistence(timeout: 3))
    app.buttons["Split"].tap()
    XCTAssertTrue(app.buttons["Join"].waitForExistence(timeout: 3))
    app.buttons["Join"].tap()
    XCTAssertTrue(app.buttons["Split"].waitForExistence(timeout: 3))
    app.buttons["1 × 2"].tap()
    XCTAssertTrue(app.steppers["control-height"].waitForExistence(timeout: 3))
    app.buttons["control-height-Increment"].tap()
    app.buttons["Apply size"].tap()
    XCTAssertTrue(app.buttons["1 × 3"].waitForExistence(timeout: 3))
    let moved = app.buttons["Volume rocker, column 1, row 2"]
    let destination = app.buttons["Grid column 6, row 3"]
    moved.press(forDuration: 0.5, thenDragTo: destination)
    XCTAssertTrue(app.buttons["Volume rocker, column 6, row 2"].waitForExistence(timeout: 3))
    app.buttons["Resize"].tap()
    let handle = app.descendants(matching: .any).matching(
      NSPredicate(format: "label == %@", "Resize Volume rocker")
    ).firstMatch
    XCTAssertTrue(handle.waitForExistence(timeout: 3))
    let point = handle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
    point.press(forDuration: 0.1, thenDragTo: point.withOffset(CGVector(dx: 0, dy: 74)))
    XCTAssertTrue(app.buttons["1 × 4"].waitForExistence(timeout: 3))
    app.buttons["Add controls"].tap()
    XCTAssertTrue(app.navigationBars["Add controls"].waitForExistence(timeout: 4))
    app.buttons["Apps"].tap()
    app.buttons["Add YouTube"].tap()
    app.navigationBars["Add controls"].buttons["Done"].tap()
    let youtube = app.buttons.matching(
      NSPredicate(format: "label BEGINSWITH %@", "YouTube, column")
    ).firstMatch
    XCTAssertTrue(youtube.waitForExistence(timeout: 4), "Editor must use the actual shortcut name")
    youtube.swipeUp()
    capture(app, "iPhone grid editor")
    app.buttons["Done"].firstMatch.tap()
    XCTAssertTrue(app.buttons["Remote menu"].waitForExistence(timeout: 4))
    app.buttons["Home"].firstMatch.tap()
    app.buttons["Remote menu"].tap()
    app.buttons["Appearance"].tap()
    XCTAssertTrue(app.navigationBars["Appearance"].waitForExistence(timeout: 4))
    app.buttons["Sky accent"].tap()
    capture(app, "iPhone appearance preview")
    app.buttons["Use color"].tap()
    app.terminate()
    app.launchArguments = ["-opentv-ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()
    XCTAssertTrue(app.buttons["Remote menu"].waitForExistence(timeout: 15))
    app.buttons["Home"].firstMatch.tap()
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = "iPhone connected remote"
    attachment.lifetime = .keepAlways
    add(attachment)
  }
  @MainActor func testNativeOKHoldAndDeleteRepeat() async throws {
    continueAfterFailure = false
    let app = try await connectAndroidFixture()
    let before = try await counts()
    let ok = app.buttons["OK"].firstMatch
    ok.press(forDuration: 0.8)
    var held = try await counts()
    for _ in 0..<20 {
      if held["okUp"]! > before["okUp"]! { break }
      try await Task.sleep(for: .milliseconds(100))
      held = try await counts()
    }
    XCTAssertEqual(held["okDown"]!, before["okDown"]! + 1)
    XCTAssertEqual(held["okUp"]!, before["okUp"]! + 1)
    XCTAssertEqual(held["okShort"], before["okShort"], "Long OK must not also send a short click")
    ok.tap()
    ok.tap()
    var tapped = try await counts()
    for _ in 0..<20 {
      if tapped["okUp"]! >= held["okUp"]! + 2 { break }
      try await Task.sleep(for: .milliseconds(100))
      tapped = try await counts()
    }
    XCTAssertEqual(tapped["okDown"]!, held["okDown"]! + 2)
    XCTAssertEqual(tapped["okUp"]!, held["okUp"]! + 2)
    XCTAssertEqual(tapped["okShort"], before["okShort"])
    let center = ok.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
    center.press(
      forDuration: 0.15, thenDragTo: center.withOffset(CGVector(dx: 0, dy: 150)),
      withVelocity: .slow, thenHoldForDuration: 0.5)
    var movedOut = try await counts()
    for _ in 0..<20 {
      if movedOut["okUp"]! > tapped["okUp"]! { break }
      try await Task.sleep(for: .milliseconds(100))
      movedOut = try await counts()
    }
    XCTAssertEqual(movedOut["okDown"]!, tapped["okDown"]! + 1)
    XCTAssertEqual(movedOut["okUp"]!, tapped["okUp"]! + 1)
    XCTAssertEqual(movedOut["okShort"], before["okShort"])
    app.buttons["Remote menu"].tap()
    app.buttons["All controls"].tap()
    let delete = app.buttons["Delete"].firstMatch
    for _ in 0..<5 {
      if delete.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(delete.isHittable)
    delete.press(forDuration: 0.8)
    let deleted = try await counts()
    XCTAssertGreaterThan(deleted["delete"]!, before["delete"]! + 1)
    try await Task.sleep(for: .milliseconds(400))
    let stopped = try await counts()
    XCTAssertEqual(stopped["delete"], deleted["delete"])
    capture(app, "Native OK hold and delete repeat")
  }
  @MainActor func testInAppLanguageSwitchAndPersistence() {
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = [
      "-opentv-ui-testing", "-opentv-reset-ui-state", "-AppleLanguages", "(en)", "-AppleLocale",
      "en_US",
    ]
    app.launch()
    XCTAssertTrue(app.buttons["Find my TV"].waitForExistence(timeout: 10))
    app.buttons["Appearance"].tap()
    XCTAssertTrue(app.buttons["Sky accent"].waitForExistence(timeout: 4))
    app.buttons["Cancel"].tap()
    app.buttons["Language"].tap()
    XCTAssertTrue(app.buttons["language-ru"].waitForExistence(timeout: 4))
    app.buttons["language-ru"].tap()
    XCTAssertTrue(app.navigationBars["Язык"].waitForExistence(timeout: 4))
    capture(app, "In-app Russian language picker")
    app.buttons["Готово"].tap()
    XCTAssertTrue(app.buttons["Найти телевизор"].waitForExistence(timeout: 4))
    app.buttons["Оформление"].tap()
    XCTAssertTrue(app.buttons["Акцент: Небесный"].waitForExistence(timeout: 4))
    app.buttons["Отмена"].tap()
    XCTAssertFalse(app.buttons["Connect TV Companion"].exists)
    app.terminate()
    app.launchArguments = ["-opentv-ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()
    XCTAssertTrue(app.buttons["Найти телевизор"].waitForExistence(timeout: 10))
    app.buttons["Язык"].tap()
    app.buttons["language-en"].tap()
    XCTAssertTrue(app.navigationBars["Language"].waitForExistence(timeout: 4))
    app.buttons["Done"].tap()
    XCTAssertTrue(app.buttons["Find my TV"].waitForExistence(timeout: 4))
    app.terminate()
    app.launch()
    XCTAssertTrue(app.buttons["Find my TV"].waitForExistence(timeout: 10))
    app.buttons["Language"].tap()
    app.buttons["language-system"].tap()
    app.buttons["Done"].tap()
    XCTAssertTrue(app.buttons["Find my TV"].waitForExistence(timeout: 4))
    capture(app, "In-app language picker English Russian System")
  }
  @MainActor func testRussianDiscoveryAndValidation() {
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = [
      "-opentv-ui-testing", "-opentv-reset-ui-state", "-AppleLanguages", "(ru)", "-AppleLocale",
      "ru_RU",
    ]
    app.launch()
    XCTAssertTrue(app.buttons["Найти телевизор"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.buttons["Оформление"].exists)
    app.buttons["Подключить по IP"].tap()
    let address = app.textFields["tv-ip"]
    XCTAssertTrue(address.waitForExistence(timeout: 4))
    address.tap()
    address.typeText("8.8.8.8")
    app.buttons["Подключиться"].tap()
    XCTAssertTrue(app.alerts["Не удалось подключиться"].waitForExistence(timeout: 4))
    XCTAssertTrue(app.staticTexts["Введите локальный IPv4-адрес, например 192.168.1.20"].exists)
    capture(app, "iPhone Russian IP validation")
    app.alerts.buttons["OK"].tap()
    app.buttons["Оформление"].tap()
    XCTAssertTrue(app.navigationBars["Оформление"].waitForExistence(timeout: 4))
    XCTAssertTrue(app.buttons["Акцент: Небесный"].exists)
    capture(app, "iPhone Russian appearance")
    app.buttons["Отмена"].tap()
    app.buttons["О OpenTV-Remote"].tap()
    XCTAssertTrue(app.navigationBars["О OpenTV-Remote"].waitForExistence(timeout: 4))
    let privacy = app.staticTexts.matching(
      NSPredicate(format: "label BEGINSWITH %@", "Нет аккаунта OpenTV-Remote")
    ).firstMatch
    for _ in 0..<4 {
      if privacy.exists { break }
      app.swipeUp()
    }
    XCTAssertTrue(privacy.exists)
    capture(app, "iPhone Russian about")
  }
  @MainActor func testRussianRemoteAndPresets() async throws {
    continueAfterFailure = false
    let app = try await connectAndroidFixture(language: "ru")
    let before = try await counts()
    app.buttons["Главная"].firstMatch.tap()
    let sent = try await counts()
    XCTAssertGreaterThan(sent["home"]!, before["home"]!)
    app.buttons["Меню пульта"].tap()
    app.collectionViews.buttons["Клавиатура"].tap()
    let field = app.descendants(matching: .any)["tv-text-input"]
    XCTAssertTrue(field.waitForExistence(timeout: 4))
    field.tap()
    field.typeText("OpenTV 42")
    app.buttons["Отправить текст"].tap()
    let feedback = app.staticTexts["text-send-feedback"]
    XCTAssertTrue(feedback.waitForExistence(timeout: 4))
    XCTAssertTrue(feedback.label.hasPrefix("Отправлено на ТВ"))
    XCTAssertEqual(field.value as? String, "OpenTV 42")
    capture(app, "iPhone Russian keyboard")
    app.buttons["Готово"].firstMatch.tap()
    app.buttons["Настроить пульт"].tap()
    app.buttons["Раскладки"].tap()
    XCTAssertTrue(app.navigationBars["Раскладки"].waitForExistence(timeout: 4))
    XCTAssertTrue(app.staticTexts["Сбалансированный пульт на каждый день"].exists)
    app.buttons["Левая рука"].tap()
    app.buttons["Правая рука"].tap()
    capture(app, "iPhone Russian preset previews")
    let lastPreset = app.buttons["preset-numbersTouch"]
    for _ in 0..<5 {
      if lastPreset.isHittable { break }
      app.swipeUp()
    }
    app.swipeUp()
    XCTAssertTrue(lastPreset.isHittable)
    XCTAssertTrue(app.staticTexts["Цифровая панель рядом с сенсорной"].exists)
    capture(app, "iPhone Russian full and numbers previews")
    lastPreset.tap()
    app.buttons["Применить раскладку"].tap()
    XCTAssertTrue(app.navigationBars["Настроить пульт"].waitForExistence(timeout: 4))
    app.buttons["Готово"].firstMatch.tap()
    XCTAssertTrue(app.buttons["Меню пульта"].waitForExistence(timeout: 4))
  }
  @MainActor func testHardwareAndroidText() async throws {
    let (data, response) = try await URLSession.shared.data(
      from: URL(string: "http://127.0.0.1:8771/hardware/android")!)
    guard (response as? HTTPURLResponse)?.statusCode == 200,
      let host = (try JSONSerialization.jsonObject(with: data) as? [String: String])?["host"],
      !host.isEmpty
    else { throw XCTSkip("No physical Android TV authorized for this run") }
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = [
      "-opentv-ui-testing", "-opentv-reset-ui-state", "-AppleLanguages", "(en)", "-AppleLocale",
      "en_US",
    ]
    app.launch()
    app.buttons["Connect by IP"].tap()
    let address = app.textFields["tv-ip"]
    XCTAssertTrue(address.waitForExistence(timeout: 4))
    address.tap()
    address.typeText(host)
    app.buttons["Connect"].tap()
    let pairing = app.textFields["TV pairing code"]
    let ready = app.buttons["Remote menu"]
    let deadline = Date().addingTimeInterval(15)
    while !pairing.exists && !ready.exists && Date() < deadline {
      try await Task.sleep(for: .milliseconds(250))
    }
    if pairing.exists {
      print("HARDWARE_ANDROID_AWAITING_USER_CODE")
      var code = ""
      let codeDeadline = Date().addingTimeInterval(90)
      while code.isEmpty && Date() < codeDeadline {
        let (data, response) = try await URLSession.shared.data(
          from: URL(string: "http://127.0.0.1:8771/hardware/android/code")!)
        if (response as? HTTPURLResponse)?.statusCode == 200 {
          code = String(decoding: data, as: UTF8.self)
        } else {
          try await Task.sleep(for: .seconds(1))
        }
      }
      XCTAssertEqual(code.count, 6, "Physical TV needs its user-visible pairing code")
      pairing.tap()
      pairing.typeText(code)
      app.buttons["Pair TV"].tap()
    }
    let connectedDeadline = Date().addingTimeInterval(15)
    while !ready.exists && !app.navigationBars["TV keyboard"].exists && Date() < connectedDeadline {
      try await Task.sleep(for: .milliseconds(250))
    }
    guard ready.exists || app.navigationBars["TV keyboard"].exists else {
      capture(app, "iPhone physical Android connection failure")
      XCTFail("Physical TV did not establish a remote session")
      return
    }
    if !app.navigationBars["TV keyboard"].exists {
      ready.tap()
      app.collectionViews.buttons["Keyboard"].tap()
    }
    let field = app.descendants(matching: .any)["tv-text-input"]
    XCTAssertTrue(field.waitForExistence(timeout: 5))
    field.tap()
    field.typeText("OpenTV test 42")
    app.buttons["Send text"].tap()
    let feedback = app.staticTexts["text-send-feedback"]
    XCTAssertTrue(feedback.waitForExistence(timeout: 5))
    XCTAssertTrue(feedback.label.hasPrefix("Sent to TV"), feedback.label)
    capture(app, "iPhone physical Android TV text sent")
    print("HARDWARE_ANDROID_TEXT_SENT_USER_DISPLAY_CONFIRMATION_REQUIRED")
  }
  @MainActor private func connectAndroidFixture(language: String = "en") async throws
    -> XCUIApplication
  {
    try await action("/samsung/stop")
    try await action("/android/ime/close")
    let russian = language == "ru"
    let app = XCUIApplication()
    app.launchArguments = [
      "-opentv-ui-testing", "-opentv-reset-ui-state", "-AppleLanguages", "(\(language))",
      "-AppleLocale", russian ? "ru_RU" : "en_US",
    ]
    app.launch()
    XCTAssertTrue(
      app.buttons[russian ? "Подключить по IP" : "Connect by IP"].waitForExistence(timeout: 10))
    app.buttons[russian ? "Подключить по IP" : "Connect by IP"].tap()
    let address = app.textFields["tv-ip"]
    XCTAssertTrue(address.waitForExistence(timeout: 4))
    address.tap()
    address.typeText("127.0.0.1")
    app.buttons[russian ? "Подключиться" : "Connect"].tap()
    let pairing = app.textFields[russian ? "Код сопряжения с ТВ" : "TV pairing code"]
    let handshakeDeadline = Date().addingTimeInterval(15)
    while !pairing.exists && !app.buttons[russian ? "Меню пульта" : "Remote menu"].exists
      && Date() < handshakeDeadline
    {
      try await Task.sleep(for: .milliseconds(200))
    }
    if pairing.exists {
      let (data, _) = try await URLSession.shared.data(
        from: URL(string: "http://127.0.0.1:8771/pair-code")!)
      let code = String(decoding: data, as: UTF8.self)
      XCTAssertEqual(code.count, 6)
      pairing.tap()
      pairing.typeText(code)
      app.buttons[russian ? "Сопрячь с ТВ" : "Pair TV"].tap()
    }
    XCTAssertTrue(
      app.buttons[russian ? "Меню пульта" : "Remote menu"].waitForExistence(timeout: 15))
    return app
  }
  @MainActor func testSamsungApprovalAndSecureReconnect() async throws {
    continueAfterFailure = false
    try await action("/samsung/start")
    let app = XCUIApplication()
    app.launchArguments = [
      "-opentv-ui-testing", "-opentv-reset-ui-state", "-AppleLanguages", "(en)", "-AppleLocale",
      "en_US",
    ]
    app.launch()
    app.buttons["Connect by IP"].tap()
    let address = app.textFields["tv-ip"]
    XCTAssertTrue(address.waitForExistence(timeout: 4))
    address.tap()
    address.typeText("127.0.0.1")
    app.buttons["Connect"].tap()
    let trust = app.buttons["Trust TV & continue"]
    if trust.waitForExistence(timeout: 8) {
      let before = try await counts()
      XCTAssertEqual(before["samsungSecure"], 0)
      capture(app, "iPhone Samsung trust")
      trust.tap()
    }
    if app.buttons["Reset Samsung pairing"].exists {
      app.buttons["Reset Samsung pairing"].tap()
      app.buttons["Reset pairing"].tap()
      XCTAssertTrue(trust.waitForExistence(timeout: 8))
      trust.tap()
    }
    try await action("/samsung/deny")
    XCTAssertTrue(app.buttons["Reconnect"].waitForExistence(timeout: 8))
    XCTAssertFalse(app.buttons["Home"].exists)
    app.buttons["Reconnect"].tap()
    try await action("/samsung/allow")
    XCTAssertTrue(app.buttons["Remote menu"].waitForExistence(timeout: 10))
    let before = try await counts()
    app.buttons["Home"].firstMatch.tap()
    let after = try await counts()
    XCTAssertGreaterThan(after["samsungHome"]!, before["samsungHome"]!)
    capture(app, "iPhone Samsung remote")
    app.buttons["Remote menu"].tap()
    app.collectionViews.buttons["Keyboard"].tap()
    let field = app.descendants(matching: .any)["tv-text-input"]
    XCTAssertTrue(field.waitForExistence(timeout: 4))
    field.tap()
    field.typeText("OpenTV iPhone")
    app.buttons["Send text"].tap()
    app.buttons["Done"].firstMatch.tap()
    let typed = try await counts()
    XCTAssertGreaterThan(typed["samsungText"]!, 0)
    app.terminate()
    app.launchArguments = ["-opentv-ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()
    XCTAssertTrue(app.buttons["Remote menu"].waitForExistence(timeout: 12))
    let reused = try await counts()
    XCTAssertGreaterThan(reused["samsungReuse"]!, 0)
    try await action("/samsung/control/rotate")
    app.terminate()
    app.launch()
    XCTAssertTrue(app.buttons["Reset Samsung pairing"].waitForExistence(timeout: 12))
    XCTAssertFalse(app.buttons["Trust TV & continue"].exists)
    let secureOnly = try await counts()
    XCTAssertEqual(secureOnly["samsungPlain"], 0)
    capture(app, "iPhone changed TV identity")
    try await action("/samsung/stop")
  }
  private func action(_ path: String) async throws {
    var request = URLRequest(url: URL(string: "http://127.0.0.1:8771" + path)!)
    request.httpMethod = "POST"
    let (_, response) = try await URLSession.shared.data(for: request)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
  }
  private func counts() async throws -> [String: Int] {
    let (data, _) = try await URLSession.shared.data(
      from: URL(string: "http://127.0.0.1:8771/counts")!)
    return try JSONDecoder().decode([String: Int].self, from: data)
  }
  @MainActor private func capture(_ app: XCUIApplication, _ name: String) {
    let screenshot = app.screenshot()
    // Preserve visual evidence even if the Xcode SDK's result-bundle collector fails after tests.
    let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("OpenTVRemoteQA")
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let file = directory.appendingPathComponent(
      name.replacingOccurrences(of: " ", with: "-") + ".png")
    if (try? screenshot.pngRepresentation.write(to: file)) != nil {
      print("OPENTV_QA_SCREENSHOT " + file.path)
    }
    let a = XCTAttachment(screenshot: screenshot)
    a.name = name
    a.lifetime = .keepAlways
    add(a)
  }
}
