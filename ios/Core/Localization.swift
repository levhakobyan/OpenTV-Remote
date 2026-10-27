import Foundation

enum AppLanguage: String, CaseIterable, Identifiable {
  case system
  case english = "en"
  case russian = "ru"
  static let defaultsKey = "app-language"
  var id: String { rawValue }
  var title: String {
    switch self {
    case .system: L10n.text("System")
    case .english: "English"
    case .russian: "Русский"
    }
  }
  func resolvedCode(preferredLanguages: [String] = Locale.preferredLanguages) -> String {
    switch self {
    case .english: "en"
    case .russian: "ru"
    case .system:
      Bundle.preferredLocalizations(from: ["en", "ru"], forPreferences: preferredLanguages).first
        ?? "en"
    }
  }
  var locale: Locale {
    self == .system ? .autoupdatingCurrent : Locale(identifier: rawValue)
  }
  static func load(from defaults: UserDefaults) -> Self {
    Self(rawValue: defaults.string(forKey: defaultsKey) ?? "") ?? .system
  }
  func save(to defaults: UserDefaults) { defaults.set(rawValue, forKey: Self.defaultsKey) }
}

/// Exact native string-table lookup. User content and protocol values never pass through this API.
enum L10n {
  private static let lock = NSLock()
  private static var selection = AppLanguage.system
  static var language: AppLanguage {
    get {
      lock.lock()
      defer { lock.unlock() }
      return selection
    }
    set {
      lock.lock()
      selection = newValue
      lock.unlock()
    }
  }
  static func text(_ key: String) -> String {
    text(key, language: language, resources: .main)
  }
  static func text(_ key: String, language: AppLanguage, resources: Bundle) -> String {
    let bundle =
      resources.path(forResource: language.resolvedCode(), ofType: "lproj")
      .flatMap(Bundle.init(path:)) ?? resources
    return NSLocalizedString(key, bundle: bundle, value: key, comment: "")
  }
  static func format(_ key: String, _ arguments: CVarArg...) -> String {
    String(format: text(key), locale: language.locale, arguments: arguments)
  }
}
