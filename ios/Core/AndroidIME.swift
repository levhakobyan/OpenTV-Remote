import Foundation

private enum IMEIdentifiers {
  static let lock = NSLock()
  static var value = 0
  static func next() -> Int {
    lock.lock()
    defer { lock.unlock() }
    value += 1
    return value
  }
}

/// Transient editor content. Never Codable, persisted, or included in diagnostic output.
struct IMETextValue: Equatable, CustomStringConvertible, CustomDebugStringConvertible {
  var text: String
  var start: Int
  var end: Int
  var description: String { "IMETextValue(redacted)" }
  var debugDescription: String { description }
  static func sameText(_ lhs: String, _ rhs: String) -> Bool {
    Array(lhs.utf16) == Array(rhs.utf16)
  }
  static func == (lhs: Self, rhs: Self) -> Bool {
    sameText(lhs.text, rhs.text) && lhs.start == rhs.start && lhs.end == rhs.end
  }
  var valid: Bool {
    let units = Array(text.utf16)
    return units.count <= 4096 && Self.boundary(start, in: units) && Self.boundary(end, in: units)
  }
  static func boundary(_ offset: Int, in units: [UInt16]) -> Bool {
    guard offset >= 0, offset <= units.count else { return false }
    return offset == 0 || offset == units.count
      || !(0xD800...0xDBFF).contains(units[offset - 1])
      || !(0xDC00...0xDFFF).contains(units[offset])
  }
}

struct IMERangeEdit: Equatable, CustomStringConvertible {
  let start: Int
  let end: Int
  let text: String
  var description: String { "IMERangeEdit(redacted)" }
  static func between(_ old: IMETextValue, _ new: IMETextValue) -> Self? {
    guard !IMETextValue.sameText(old.text, new.text) else { return nil }
    let a = Array(old.text.utf16)
    let b = Array(new.text.utf16)
    if new.start == new.end {
      let caret = new.end
      let oldEnd = a.count - (b.count - caret)
      if IMETextValue.boundary(caret, in: b), IMETextValue.boundary(oldEnd, in: a),
        Array(a[oldEnd...]) == Array(b[caret...])
      {
        var prefix = 0
        while prefix < min(a.count, b.count), a[prefix] == b[prefix] { prefix += 1 }
        var start = min(prefix, caret, oldEnd)
        while !IMETextValue.boundary(start, in: a) || !IMETextValue.boundary(start, in: b) {
          start -= 1
        }
        return Self(
          start: start, end: oldEnd,
          text: String(decoding: b[start..<caret], as: UTF16.self))
      }
    }
    return between(old.text, new.text)
  }
  static func between(_ old: String, _ new: String) -> Self? {
    guard !IMETextValue.sameText(old, new) else { return nil }
    let a = Array(old.utf16)
    let b = Array(new.utf16)
    var prefix = 0
    while prefix < min(a.count, b.count), a[prefix] == b[prefix] { prefix += 1 }
    while !IMETextValue.boundary(prefix, in: a) || !IMETextValue.boundary(prefix, in: b) {
      prefix -= 1
    }
    var suffix = 0
    while suffix < min(a.count, b.count) - prefix,
      a[a.count - suffix - 1] == b[b.count - suffix - 1]
    { suffix += 1 }
    while !IMETextValue.boundary(a.count - suffix, in: a)
      || !IMETextValue.boundary(b.count - suffix, in: b)
    { suffix -= 1 }
    return Self(
      start: prefix, end: a.count - suffix,
      text: String(decoding: b[prefix..<(b.count - suffix)], as: UTF16.self))
  }
  func message(ime: Int, field: Int) -> Data {
    let object = Wire.integer(1, start) + Wire.integer(2, end) + Wire.string(3, text)
    return Wire.bytes(
      21,
      Wire.integer(1, ime) + Wire.integer(2, field)
        + Wire.bytes(3, Wire.integer(1, 1) + Wire.bytes(2, object)))
  }
}

enum IMESyncMode: Equatable { case manual, live, waiting, conflict, closed }
struct IMEKeyboardState: Equatable, CustomStringConvertible {
  var session: Int = 0
  var value: IMETextValue?
  var reviewDraft: IMETextValue?
  var mode: IMESyncMode = .manual
  var privateField = false
  var active = false
  var showRequest = 0
  var composing = false
  var description: String { "IMEKeyboardState(\(mode), redacted)" }
  var canEdit: Bool { mode == .live || mode == .waiting }
}

/// One acknowledged range edit at a time. Field counters are revisions, not field identities.
struct AndroidIMEState {
  private struct FieldContext: Hashable {
    let app: String
    let counter: Int?
    let label: String
  }
  private struct Pending {
    let session: Int
    let previousCounter: Int
    let expected: IMETextValue
    let revision: Int
    let ime: Int
    let appCounter: Int?
  }
  private(set) var keyboard = IMEKeyboardState(session: IMEIdentifiers.next())
  private var counters = (ime: 0, field: 0)
  private var sawIME = false
  private var base: IMETextValue?
  private var draft: IMETextValue?
  private var revision = 0
  private var pending: Pending?
  private var app = ""
  private var appCounter: Int?
  private var label = ""
  private var showID = 0
  private var manualUntilShow = false
  private var retiredContexts: Set<FieldContext> = []
  // Labels may disappear in TV echoes; only app/IME context resets private classification.
  private var protectedContext: FieldContext?
  private var highestFieldCounter = 0
  private var manualSelection: (start: Int, end: Int)?

  mutating func receive(_ fields: [Wire.Field]) throws {
    if fields.has(21) {
      let batch = try fields.nested(21)
      let ime = batch.number(1)
      let field = batch.number(2)
      if ime >= 0, field >= 0, !(sawIME && ime != 0 && ime < counters.ime) {
        let changed = sawIME && (ime != 0 || counters.ime == 0) && ime != counters.ime
        if changed {
          protectedContext = nil
          stopEditing()
          manualUntilShow = false
        }
        sawIME = true
        if ime != 0 || counters.ime == 0 { counters.ime = ime }
        // A zero batch is ambiguous and must not erase a real field revision.
        if field != 0 || counters.field == 0 { counters.field = max(counters.field, field) }
        highestFieldCounter = max(highestFieldCounter, counters.field)
        if base != nil, keyboard.active, !manualUntilShow,
          keyboard.mode == .manual, !keyboard.privateField
        {
          keyboard.mode = .live
        }
      }
    }
    if fields.has(20) {
      let message = try fields.nested(20)
      let info = try message.nested(1)
      let package = Self.string(info, 12) ?? app
      if !message.has(2) {
        let nextCounter = info.has(1) ? info.number(1) : appCounter
        if package != app || nextCounter != appCounter {
          retiredContexts.insert(FieldContext(app: app, counter: appCounter, label: label))
        }
        if package != app || !info.isEmpty { close() }
        app = package
        if info.has(1) { appCounter = info.number(1) }
      } else {
        receiveStatus(
          try message.nested(2), package: package,
          context: info.has(1) ? info.number(1) : appCounter, show: false)
      }
    }
    if fields.has(22) {
      let show = try fields.nested(22)
      guard show.has(2) else {
        close()
        return
      }
      showID += 1
      receiveStatus(try show.nested(2), package: app, context: appCounter, show: true)
    }
  }
  private static func string(_ fields: [Wire.Field], _ n: Int) -> String? {
    guard let bytes = fields.first(where: { $0.number == n })?.bytes else { return nil }
    return String(data: bytes, encoding: .utf8)
  }
  private mutating func receiveStatus(
    _ fields: [Wire.Field], package: String, context: Int?, show: Bool
  ) {
    let counter = fields.number(1)
    let start = fields.number(3)
    let end = fields.number(4)
    let decoded = Self.string(fields, 2)
    let newLabel = Self.string(fields, 6) ?? label
    let contextChanged = package != app || context != appCounter || newLabel != label
    let privacyContext = FieldContext(app: package, counter: context, label: "")
    let privateField =
      protectedContext == privacyContext
      || decoded.map(Self.masked) == true || Self.passwordLabel(newLabel)
    guard counter >= 0 else {
      close()
      return
    }
    let incomingContext = FieldContext(app: package, counter: context, label: newLabel)
    if contextChanged, retiredContexts.contains(incomingContext) {
      if !show { return }
      if counter <= highestFieldCounter {
        let unsent = draft ?? base
        let confirmed = base
        let latest = counters.field
        newField(
          package: app, context: appCounter, label: label, counter: latest,
          value: privateField ? nil : confirmed, privateField: privateField)
        keyboard.reviewDraft = keyboard.privateField ? nil : unsent
        keyboard.mode = .conflict
        manualUntilShow = true
        return
      }
    }
    if start < 0 || end < 0 {
      if !show, !contextChanged, keyboard.active, keyboard.mode == .manual, base == nil {
        counters.field = max(counters.field, counter)
        keyboard.privateField = keyboard.privateField || privateField
        manualSelection = nil
        return
      }
      let unsent = privateField ? nil : draft
      newField(
        package: package, context: context, label: newLabel, counter: counter,
        value: nil, privateField: privateField)
      keyboard.showRequest = 0
      keyboard.reviewDraft = unsent
      manualUntilShow = true
      return
    }
    guard !fields.has(2) || decoded != nil else {
      newField(
        package: package, context: context, label: newLabel, counter: counter,
        value: nil, privateField: privateField)
      return
    }
    let text = decoded ?? ""
    let incoming = IMETextValue(text: text, start: start, end: end)
    // Nonempty readback requires a value field; omitted empty proto3 values remain valid.
    guard incoming.valid else {
      newField(
        package: package, context: context, label: newLabel, counter: counter, value: nil,
        privateField: privateField)
      return
    }
    if !show, package == app, context == appCounter, newLabel == label, base != nil,
      counter < counters.field
    {
      return
    }
    if privateField {
      if !show, protectedContext == privacyContext {
        if counter >= counters.field {
          counters.field = counter
          manualSelection = (min(start, end), max(start, end))
        }
        keyboard.privateField = true
        return
      }
      newField(
        package: package, context: context, label: newLabel, counter: counter, value: nil,
        privateField: true)
      manualSelection = (min(start, end), max(start, end))
      return
    }
    if manualUntilShow && !show && !contextChanged {
      if counter >= counters.field {
        counters.field = counter
        base = incoming
        keyboard.value = incoming
        manualSelection = (min(start, end), max(start, end))
      }
      return
    }
    if show || contextChanged { manualUntilShow = false }
    if show, !contextChanged, let confirmed = base, counter < counters.field {
      let unsent = draft ?? confirmed
      let latest = counters.field
      newField(
        package: package, context: context, label: newLabel, counter: latest,
        value: confirmed, privateField: false)
      keyboard.reviewDraft = unsent
      keyboard.mode = .conflict
      manualUntilShow = true
      return
    }
    if show {
      let interrupted =
        pending != nil || draft != base || keyboard.composing || keyboard.reviewDraft != nil
      let unsent = interrupted ? keyboard.reviewDraft ?? draft : nil
      newField(
        package: package, context: context, label: newLabel, counter: counter,
        value: incoming, privateField: false)
      if interrupted {
        keyboard.mode = .conflict
        keyboard.reviewDraft = unsent
        manualUntilShow = true
      }
      return
    }
    if let pending, !contextChanged, pending.ime == counters.ime,
      pending.appCounter == context, counter > pending.previousCounter,
      IMETextValue.sameText(incoming.text, pending.expected.text)
    {
      counters.field = counter
      base = incoming
      manualSelection = (min(start, end), max(start, end))
      self.pending = nil
      if revision == pending.revision, !keyboard.composing { draft = incoming }
      keyboard.value = draft ?? incoming
      keyboard.mode = .live
      return
    }
    if pending != nil, !contextChanged, counter <= pending!.previousCounter { return }
    if pending != nil {
      // An uncorrelated TV edit wins; never apply an old phone range to a changed field.
      let unsent = draft
      newField(
        package: package, context: context, label: newLabel, counter: counter, value: incoming,
        privateField: false)
      keyboard.mode = .conflict
      keyboard.reviewDraft = unsent
      manualUntilShow = true
      return
    }
    if keyboard.composing, !contextChanged, let base {
      if incoming != base, !IMETextValue.sameText(draft?.text ?? "", base.text) {
        let unsent = draft
        newField(
          package: package, context: context, label: newLabel, counter: counter,
          value: incoming, privateField: false)
        keyboard.mode = .conflict
        keyboard.reviewDraft = unsent
        manualUntilShow = true
        return
      }
      if incoming == base {
        // Counter-only echoes must not disturb an acknowledged marked word or local caret.
        counters.field = max(counters.field, counter)
        return
      }
      // The provisional phone word is already acknowledged. An actual TV document or
      // selection change is authoritative and may end the local marked-text session.
      keyboard.composing = false
    }
    if contextChanged || base == nil {
      newField(
        package: package, context: context, label: newLabel, counter: counter, value: incoming,
        privateField: false)
    } else {
      counters.field = counter
      base = incoming
      draft = incoming
      manualSelection = (min(start, end), max(start, end))
      keyboard.value = incoming
      keyboard.active = true
      keyboard.mode = sawIME ? .live : .manual
    }
  }
  private mutating func newField(
    package: String, context: Int?, label: String, counter: Int, value: IMETextValue?,
    privateField: Bool
  ) {
    let previousContext = FieldContext(app: app, counter: appCounter, label: self.label)
    let nextContext = FieldContext(app: package, counter: context, label: label)
    if previousContext != nextContext, !app.isEmpty || appCounter != nil || !self.label.isEmpty {
      retiredContexts.insert(previousContext)
    }
    retiredContexts.remove(nextContext)
    highestFieldCounter = max(highestFieldCounter, counter)
    let privacyContext = FieldContext(app: package, counter: context, label: "")
    if privateField {
      protectedContext = privacyContext
    } else if protectedContext != privacyContext {
      protectedContext = nil
    }
    showID += 1
    pending = nil
    revision = 0
    app = package
    appCounter = context
    self.label = label
    counters.field = counter
    base = privateField ? nil : value
    draft = base
    manualSelection = value.map { (min($0.start, $0.end), max($0.start, $0.end)) }
    keyboard = IMEKeyboardState(
      session: IMEIdentifiers.next(), value: base,
      mode: sawIME && base != nil && !privateField ? .live : .manual,
      privateField: privateField, active: true, showRequest: showID)
  }
  static func masked(_ text: String) -> Bool {
    text.unicodeScalars.contains { "•●▪◦·".unicodeScalars.contains($0) }
      || text.contains("**") || !text.isEmpty && text.allSatisfy { $0 == "*" }
  }
  private static func passwordLabel(_ label: String) -> Bool {
    let lower = label.lowercased()
    return ["password", "passwd", "парол", "գաղտնաբառ"].contains { lower.contains($0) }
      || lower.range(of: #"(?:^|\W)(?:pin|passcode)(?:$|\W)"#, options: .regularExpression) != nil
  }
  mutating func stage(_ value: IMETextValue, session: Int, composing: Bool = false) -> Bool {
    guard keyboard.session == session, keyboard.canEdit, value.valid else { return false }
    if draft != value {
      revision += 1
      draft = value
      keyboard.value = value
    }
    keyboard.composing = composing
    return true
  }
  mutating func nextEdit(session: Int) -> Data? {
    guard keyboard.session == session, keyboard.canEdit, pending == nil,
      let base, let draft, let edit = IMERangeEdit.between(base, draft)
    else { return nil }
    pending = Pending(
      session: session, previousCounter: counters.field, expected: draft,
      revision: revision, ime: counters.ime, appCounter: appCounter)
    keyboard.mode = .waiting
    return edit.message(ime: counters.ime, field: counters.field)
  }
  mutating func expire(session: Int) {
    guard keyboard.session == session, pending != nil else { return }
    keyboard.reviewDraft = draft
    pending = nil
    base = nil
    draft = nil
    keyboard.value = nil
    keyboard.mode = .manual
    manualUntilShow = true
  }
  mutating func stopEditing() {
    pending = nil
    draft = nil
    base = nil
    manualSelection = nil
    manualUntilShow = true
    keyboard = IMEKeyboardState(
      session: IMEIdentifiers.next(), mode: .manual,
      privateField: protectedContext == FieldContext(app: app, counter: appCounter, label: ""))
  }
  mutating func close() {
    stopEditing()
    manualUntilShow = false
    keyboard.mode = .closed
  }
  var waiting: Bool { pending != nil }
  var localRevision: Int { revision }
  var readyToSubmit: Bool {
    keyboard.active && keyboard.canEdit && !keyboard.composing && !waiting
      && draft.map { value in base.map { IMETextValue.sameText(value.text, $0.text) } ?? false }
        == true
  }
  /// Explicit manual insertion retains compatibility with TVs without full field readback.
  func insertion(_ value: String) throws -> Data {
    guard !value.isEmpty, value.utf16.count <= 1000 else {
      throw RemoteError.message(L10n.text("Enter up to 1,000 characters"))
    }
    let offset = value.utf16.count - 1
    let range = manualSelection ?? (offset, offset)
    return IMERangeEdit(start: range.0, end: range.1, text: value).message(
      ime: counters.ime, field: counters.field)
  }
}
