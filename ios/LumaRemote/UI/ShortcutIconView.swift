import SwiftUI

struct ShortcutIconView: View {
  let app: AppShortcut
  var body: some View {
    Group {
      if app.target == AppShortcut.catalog[0].target {
        Image("YouTube").resizable().scaledToFit()
      } else if app.target == AppShortcut.catalog[1].target {
        Image("Netflix").resizable().scaledToFit()
      } else {
        Image(systemName: app.symbol).resizable().scaledToFit()
      }
    }.frame(width: 30, height: 30).accessibilityHidden(true)
  }
}
