import XCTest

@testable import OpenTVRemoteCore

final class AndroidIMETests: XCTestCase {
  private func status(
    _ text: String, counter: Int = 42, start: Int? = nil, end: Int? = nil,
    label: String = "Search", context: Int = 7, show: Bool = false
  ) -> Data {
    let position = text.utf16.count
    let value =
      Wire.integer(1, counter) + Wire.string(2, text)
      + Wire.integer(3, start ?? position) + Wire.integer(4, end ?? position)
      + Wire.string(6, label)
    if show { return Wire.bytes(22, Wire.bytes(2, value)) }
    return Wire.bytes(
      20,
      Wire.bytes(1, Wire.integer(1, context) + Wire.string(12, "com.luma.fixture"))
        + Wire.bytes(2, value))
  }
  private func opened(_ text: String = "ab") throws -> AndroidIMEState {
    var ime = AndroidIMEState()
    try ime.receive(Wire.parse(status(text)))
    try ime.receive(Wire.parse(Wire.bytes(21, Wire.integer(1, 7) + Wire.integer(2, 42))))
    try ime.receive(Wire.parse(status(text, show: true)))
    return ime
  }
  private func value(_ text: String, caret: Int? = nil) -> IMETextValue {
    IMETextValue(text: text, start: caret ?? text.utf16.count, end: caret ?? text.utf16.count)
  }
  func testSharedUTF16RangeAndWireVectors() throws {
    struct Fixture: Decodable { var cases: [Vector] }
    struct Vector: Decodable {
      var name: String
      var before: String
      var after: String
      var selectionStart: Int
      var selectionEnd: Int
      var start: Int
      var end: Int
      var replacement: String
      var imeCounter: Int
      var fieldCounter: Int
      var wireHex: String
    }
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent().deletingLastPathComponent()
    let fixture = try JSONDecoder().decode(
      Fixture.self,
      from: Data(contentsOf: root.appendingPathComponent("shared/ime-edit-fixtures.json")))
    XCTAssertEqual(fixture.cases.count, 12)
    for v in fixture.cases {
      let edit = try XCTUnwrap(
        IMERangeEdit.between(
          value(v.before), IMETextValue(text: v.after, start: v.selectionStart, end: v.selectionEnd)
        ), v.name)
      XCTAssertEqual(edit.start, v.start, v.name)
      XCTAssertEqual(edit.end, v.end, v.name)
      XCTAssertEqual(edit.text, v.replacement, v.name)
      XCTAssertEqual(
        edit.message(ime: v.imeCounter, field: v.fieldCounter).map { String(format: "%02x", $0) }
          .joined(), v.wireHex, v.name)
    }
  }
  func testTVSnapshotIncludesSelectionAndExplicitZeroCounters() throws {
    var ime = AndroidIMEState()
    try ime.receive(Wire.parse(Wire.bytes(21, Data())))
    try ime.receive(Wire.parse(status("բարև🙂", counter: 0, start: 0, end: 4)))
    XCTAssertEqual(ime.keyboard.value, IMETextValue(text: "բարև🙂", start: 0, end: 4))
    XCTAssertEqual(ime.keyboard.mode, .live)
    XCTAssertGreaterThan(ime.keyboard.showRequest, 0)
  }
  func testSingleFlightPreservesNewerTypingAndIgnoresStaleEcho() throws {
    var ime = try opened()
    let session = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abc"), session: session))
    XCTAssertNotNil(ime.nextEdit(session: session))
    XCTAssertTrue(ime.stage(value("abcd"), session: session))
    XCTAssertNil(ime.nextEdit(session: session))
    try ime.receive(Wire.parse(status("abc", counter: 43)))
    XCTAssertEqual(ime.keyboard.value?.text, "abcd")
    XCTAssertEqual(
      ime.nextEdit(session: session),
      IMERangeEdit(start: 3, end: 3, text: "d").message(ime: 7, field: 43))
    try ime.receive(Wire.parse(status("abc", counter: 42)))
    XCTAssertEqual(ime.keyboard.value?.text, "abcd")
    XCTAssertTrue(ime.waiting)
    try ime.receive(Wire.parse(status("abcd", counter: 44)))
    XCTAssertNil(ime.nextEdit(session: session))
    XCTAssertFalse(ime.waiting)
  }
  func testCounterOnlyBatchIsNotAnAcknowledgmentAndZeroDoesNotRegress() throws {
    var ime = try opened()
    let session = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abc"), session: session))
    _ = ime.nextEdit(session: session)
    try ime.receive(Wire.parse(Wire.bytes(21, Wire.integer(1, 7) + Wire.integer(2, 43))))
    XCTAssertTrue(ime.waiting)
    try ime.receive(Wire.parse(status("abc", counter: 43)))
    try ime.receive(Wire.parse(Wire.bytes(21, Data())))
    XCTAssertTrue(ime.stage(value("abc "), session: session))
    XCTAssertEqual(
      ime.nextEdit(session: session),
      IMERangeEdit(start: 3, end: 3, text: " ").message(ime: 7, field: 43))
  }
  func testTimeoutNeverResendsAndPreservesDraftForExplicitReview() throws {
    var ime = try opened()
    let session = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abc"), session: session))
    _ = ime.nextEdit(session: session)
    ime.expire(session: session)
    XCTAssertEqual(ime.keyboard.mode, .manual)
    XCTAssertEqual(ime.keyboard.reviewDraft?.text, "abc")
    try ime.receive(Wire.parse(Wire.bytes(21, Wire.integer(1, 7) + Wire.integer(2, 43))))
    try ime.receive(Wire.parse(status("abc", counter: 43)))
    XCTAssertEqual(ime.keyboard.mode, .manual)
    XCTAssertNil(ime.nextEdit(session: session))
  }
  func testRemoteConflictKeepsNewerDraftButNeverReplaysIt() throws {
    var ime = try opened()
    let session = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abc"), session: session))
    _ = ime.nextEdit(session: session)
    XCTAssertTrue(ime.stage(value("abcd"), session: session))
    try ime.receive(Wire.parse(status("TV change", counter: 43)))
    XCTAssertEqual(ime.keyboard.mode, .conflict)
    XCTAssertEqual(ime.keyboard.value?.text, "TV change")
    XCTAssertEqual(ime.keyboard.reviewDraft?.text, "abcd")
    XCTAssertFalse(ime.stage(value("old field"), session: session))
    XCTAssertNil(ime.nextEdit(session: ime.keyboard.session))
  }
  func testMatchingShowRequestCannotAcknowledgeOrReplayOldFieldDraft() throws {
    var ime = try opened()
    let session = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abc"), session: session))
    _ = ime.nextEdit(session: session)
    XCTAssertTrue(ime.stage(value("abcd"), session: session))
    try ime.receive(Wire.parse(status("abc", counter: 43, show: true)))
    XCTAssertNotEqual(ime.keyboard.session, session)
    XCTAssertEqual(ime.keyboard.mode, .conflict)
    XCTAssertEqual(ime.keyboard.reviewDraft?.text, "abcd")
    XCTAssertNil(ime.nextEdit(session: ime.keyboard.session))
  }
  func testCloseAndDismissRejectOldCallbacks() throws {
    var ime = try opened()
    let session = ime.keyboard.session
    ime.stopEditing()
    XCTAssertFalse(ime.stage(value("late"), session: session))
    try ime.receive(Wire.parse(status("late", counter: 43)))
    XCTAssertEqual(ime.keyboard.mode, .manual)
    try ime.receive(Wire.parse(Wire.bytes(20, Wire.bytes(1, Wire.string(12, "com.luma.other")))))
    XCTAssertEqual(ime.keyboard.mode, .closed)
    XCTAssertNil(ime.keyboard.value)
  }
  func testPasswordMaskedInvalidUTF8AndSurrogateBoundariesNeverExposeReadback() throws {
    for (text, label) in [
      ("••••", "Search"), ("••••x", ""), ("secret", "Password"), ("1234", "PIN"),
    ] {
      var ime = try opened()
      try ime.receive(Wire.parse(status(text, counter: 43, label: label, show: true)))
      XCTAssertEqual(ime.keyboard.mode, .manual)
      XCTAssertNil(ime.keyboard.value)
      XCTAssertTrue(ime.keyboard.privateField)
      XCTAssertFalse(String(reflecting: ime.keyboard).contains(text))
    }
    var ime = try opened()
    try ime.receive(Wire.parse(status("🙂", counter: 43, start: 1, end: 1)))
    XCTAssertNil(ime.keyboard.value)
    XCTAssertEqual(ime.keyboard.mode, .manual)
    let invalid =
      Wire.integer(1, 44) + Wire.bytes(2, Data([0xff])) + Wire.integer(3, 0) + Wire.integer(4, 0)
    try ime.receive(Wire.parse(Wire.bytes(22, Wire.bytes(2, invalid))))
    XCTAssertNil(ime.keyboard.value)
    XCTAssertEqual(ime.keyboard.mode, .manual)
  }
  func testProvisionalCompositionIsLiveAndExternalTVChangeFencesCommit() throws {
    var ime = try opened()
    let session = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abՀայ"), session: session, composing: true))
    XCTAssertEqual(
      ime.nextEdit(session: session),
      IMERangeEdit(start: 2, end: 2, text: "Հայ").message(ime: 7, field: 42))
    XCTAssertFalse(ime.readyToSubmit)
    try ime.receive(Wire.parse(status("external TV edit", counter: 43)))
    XCTAssertEqual(ime.keyboard.mode, .conflict)
    XCTAssertEqual(ime.keyboard.value?.text, "external TV edit")
    XCTAssertEqual(ime.keyboard.reviewDraft?.text, "abՀայ")
    XCTAssertFalse(ime.stage(value("abՀայերեն"), session: session, composing: false))
    XCTAssertNil(ime.nextEdit(session: ime.keyboard.session))
    var normal = try opened()
    let token = normal.keyboard.session
    XCTAssertTrue(normal.stage(value("abПр"), session: token, composing: true))
    XCTAssertEqual(
      normal.nextEdit(session: token),
      IMERangeEdit(start: 2, end: 2, text: "Пр").message(ime: 7, field: 42))
    try normal.receive(Wire.parse(status("abПр", counter: 43, start: 2, end: 2)))
    XCTAssertEqual(normal.keyboard.value, value("abПр"))
    XCTAssertTrue(normal.keyboard.composing)
    XCTAssertTrue(normal.stage(value("abПривет"), session: token, composing: true))
    XCTAssertEqual(
      normal.nextEdit(session: token),
      IMERangeEdit(start: 4, end: 4, text: "ивет").message(ime: 7, field: 43))
    try normal.receive(Wire.parse(status("abПривет", counter: 44)))
    XCTAssertTrue(normal.stage(value("abПривет"), session: token, composing: false))
    XCTAssertNil(
      normal.nextEdit(session: token),
      "Committing an already mirrored provisional word must not duplicate it")
    XCTAssertTrue(normal.readyToSubmit)
  }
  func testConfirmedMarkedWordAcceptsTVChangeButCounterEchoPreservesPhoneSelection() throws {
    var ime = try opened()
    let token = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abՀայ", caret: 3), session: token, composing: true))
    XCTAssertNotNil(ime.nextEdit(session: token))
    try ime.receive(Wire.parse(status("abՀայ", counter: 43)))
    XCTAssertTrue(ime.keyboard.composing)
    XCTAssertEqual(ime.keyboard.value?.start, 3)
    try ime.receive(Wire.parse(status("abՀայ", counter: 44)))
    XCTAssertTrue(ime.keyboard.composing)
    XCTAssertEqual(ime.keyboard.value?.start, 3)
    try ime.receive(Wire.parse(status("բարև🙂 Привет", counter: 45, start: 4, end: 4)))
    XCTAssertEqual(ime.keyboard.session, token)
    XCTAssertEqual(ime.keyboard.mode, .live)
    XCTAssertFalse(ime.keyboard.composing)
    XCTAssertEqual(ime.keyboard.value, IMETextValue(text: "բարև🙂 Привет", start: 4, end: 4))
    XCTAssertNil(ime.nextEdit(session: token))
  }
  func testOldIMECounterCannotEraseNewerDraft() throws {
    var ime = try opened()
    let token = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("new local"), session: token))
    _ = ime.nextEdit(session: token)
    try ime.receive(Wire.parse(Wire.bytes(21, Wire.integer(1, 6) + Wire.integer(2, 1))))
    XCTAssertEqual(ime.keyboard.value?.text, "new local")
    XCTAssertEqual(ime.keyboard.session, token)
    XCTAssertTrue(ime.waiting)
  }
  func testFieldTokensCannotCollideAcrossProvidersAndCanceledWorkCannotResume() throws {
    var old = try opened()
    let token = old.keyboard.session
    var new = try opened()
    XCTAssertNotEqual(token, new.keyboard.session)
    XCTAssertFalse(new.stage(value("from previous TV"), session: token))
    XCTAssertNil(new.nextEdit(session: token))
    old.stopEditing()
    XCTAssertFalse(old.stage(value("late callback"), session: token))
    XCTAssertEqual(new.keyboard.value?.text, "ab")
  }
  func testLateShowAfterAcknowledgmentCannotRegressConfirmedTextOrRevision() throws {
    var ime = try opened("abc")
    let token = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abcd"), session: token))
    _ = ime.nextEdit(session: token)
    try ime.receive(Wire.parse(status("abcd", counter: 43)))
    try ime.receive(Wire.parse(status("abc", counter: 42, show: true)))
    XCTAssertEqual(ime.keyboard.mode, .conflict)
    XCTAssertEqual(ime.keyboard.value?.text, "abcd")
    XCTAssertEqual(ime.keyboard.reviewDraft?.text, "abcd")
    XCTAssertNil(ime.nextEdit(session: ime.keyboard.session))
    let manual = try Wire.parse(ime.insertion("next")).nested(21)
    XCTAssertEqual(manual.number(2), 43)
  }
  func testLateAcknowledgmentFromRetiredFieldCannotReplaceNewField() throws {
    var ime = try opened()
    let token = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abc"), session: token))
    _ = ime.nextEdit(session: token)
    try ime.receive(Wire.parse(status("new field", counter: 100, context: 8)))
    try ime.receive(Wire.parse(status("new field", counter: 100, context: 8, show: true)))
    let next = ime.keyboard.session
    try ime.receive(Wire.parse(status("abc", counter: 43, context: 7)))
    XCTAssertEqual(ime.keyboard.value?.text, "new field")
    XCTAssertEqual(ime.keyboard.session, next)
    XCTAssertFalse(ime.stage(value("old callback"), session: token))
    XCTAssertNil(ime.nextEdit(session: next))
    try ime.receive(Wire.parse(status("abc", counter: 43, context: 7, show: true)))
    XCTAssertEqual(ime.keyboard.value?.text, "new field")
    XCTAssertEqual(ime.keyboard.mode, .conflict)
  }
  func testPrivateEchoDoesNotReopenAndNeverUnmasksPreviouslyProtectedContext() throws {
    var ime = try opened()
    try ime.receive(Wire.parse(status("••••x", counter: 43, label: "")))
    let token = ime.keyboard.session
    let request = ime.keyboard.showRequest
    try ime.receive(Wire.parse(status("••••xy", counter: 44, label: "")))
    XCTAssertEqual(ime.keyboard.session, token)
    XCTAssertEqual(ime.keyboard.showRequest, request)
    XCTAssertNil(ime.keyboard.value)
    let edit = try Wire.parse(ime.insertion("Z")).nested(21).nested(3).nested(2)
    XCTAssertEqual(edit.number(1), 6)
    XCTAssertEqual(edit.number(2), 6)
    try ime.receive(Wire.parse(status("not safe to reveal", counter: 45, label: "")))
    XCTAssertTrue(ime.keyboard.privateField)
    XCTAssertEqual(ime.keyboard.mode, .manual)
    XCTAssertNil(ime.keyboard.value)
    XCTAssertNil(ime.keyboard.reviewDraft)
    let protectedInvalid =
      Wire.integer(1, 46) + Wire.bytes(2, Data([0xff]))
      + Wire.integer(3, 0) + Wire.integer(4, 0) + Wire.string(6, "Password")
    try ime.receive(Wire.parse(Wire.bytes(22, Wire.bytes(2, protectedInvalid))))
    XCTAssertTrue(ime.keyboard.privateField)
    XCTAssertNil(ime.keyboard.value)
  }
  func testNewFieldShowPreservesUnsentReviewAcrossMetadataAndShowFrames() throws {
    var ime = try opened()
    let token = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("abc"), session: token))
    _ = ime.nextEdit(session: token)
    XCTAssertTrue(ime.stage(value("abcd"), session: token))
    try ime.receive(Wire.parse(status("fresh", counter: 100, context: 8)))
    try ime.receive(Wire.parse(status("fresh", counter: 100, context: 8, show: true)))
    XCTAssertEqual(ime.keyboard.mode, .conflict)
    XCTAssertEqual(ime.keyboard.value?.text, "fresh")
    XCTAssertEqual(ime.keyboard.reviewDraft?.text, "abcd")
    XCTAssertNil(ime.nextEdit(session: ime.keyboard.session))
  }
  func testNegativeSelectionIsManualNotClosureAndDoesNotAutoOpenOrReplay() throws {
    var ime = try opened()
    let old = ime.keyboard.session
    XCTAssertTrue(ime.stage(value("pending phone text"), session: old))
    _ = ime.nextEdit(session: old)
    try ime.receive(Wire.parse(status("opaque", counter: 43, start: -1, end: -1)))
    XCTAssertEqual(ime.keyboard.mode, .manual)
    XCTAssertTrue(ime.keyboard.active)
    XCTAssertEqual(ime.keyboard.showRequest, 0)
    XCTAssertNil(ime.keyboard.value)
    XCTAssertEqual(ime.keyboard.reviewDraft?.text, "pending phone text")
    XCTAssertFalse(ime.stage(value("late"), session: old))
    let fallback = ime.keyboard.session
    try ime.receive(Wire.parse(status("opaque", counter: 44, start: -1, end: -1)))
    XCTAssertEqual(ime.keyboard.session, fallback)
    XCTAssertNil(ime.nextEdit(session: fallback))
    try ime.receive(
      Wire.parse(status("opaque", counter: 45, start: -1, end: -1, label: "Password", show: true)))
    XCTAssertTrue(ime.keyboard.privateField)
    XCTAssertNil(ime.keyboard.reviewDraft)
    XCTAssertEqual(ime.keyboard.showRequest, 0)
  }
  func testPasswordLabelDroppingCannotUnmaskOrEnableLearningInSameContext() throws {
    var ime = try opened()
    try ime.receive(Wire.parse(status("opaque", counter: 43, label: "Password")))
    let token = ime.keyboard.session
    let request = ime.keyboard.showRequest
    try ime.receive(Wire.parse(status("must stay private", counter: 44, label: "")))
    XCTAssertTrue(ime.keyboard.privateField)
    XCTAssertEqual(ime.keyboard.mode, .manual)
    XCTAssertNil(ime.keyboard.value)
    XCTAssertEqual(ime.keyboard.session, token)
    XCTAssertEqual(ime.keyboard.showRequest, request)
    ime.stopEditing()
    try ime.receive(Wire.parse(status("still private after dismissal", counter: 45, label: "")))
    XCTAssertTrue(ime.keyboard.privateField)
    XCTAssertNil(ime.keyboard.value)
    XCTAssertEqual(ime.keyboard.showRequest, 0)
    ime.close()
    try ime.receive(Wire.parse(status("late private echo", counter: 46, label: "")))
    XCTAssertTrue(ime.keyboard.privateField)
    XCTAssertEqual(ime.keyboard.mode, .closed)
    XCTAssertNil(ime.keyboard.value)
  }
}
