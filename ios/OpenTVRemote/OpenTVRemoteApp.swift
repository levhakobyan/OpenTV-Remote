import SwiftUI

@main struct OpenTVRemoteApp: App {
  @State private var store: RemoteStore
  init() {
    var defaults = UserDefaults.standard
    #if DEBUG
      if ProcessInfo.processInfo.arguments.contains("-opentv-ui-testing") {
        let suite = "com.opentvremote.ios.uitests"
        if ProcessInfo.processInfo.arguments.contains("-opentv-reset-ui-state") {
          UserDefaults.standard.removePersistentDomain(forName: suite)
        }
        defaults = UserDefaults(suiteName: suite)!
      }
    #endif
    _store = State(initialValue: RemoteStore(defaults: defaults))
  }
  var body: some Scene { WindowGroup { RootView().environment(store) } }
}
