import SwiftUI

struct CommandKey: View {
  let command: RemoteCommand
  var label = false
  var prominent = false
  var transparent = false
  var enabled = true
  @Environment(RemoteStore.self) private var store
  @Environment(\.remotePalette) private var p
  @State private var pressed = false
  @GestureState private var contact = false
  @State private var holdToken = UUID()
  @State private var keySize = CGSize.zero
  @State private var pressContact = RemotePressContact()
  var body: some View {
    Button {
      if !store.canHold(command) {
        store.dispatch(command)
        haptic()
      }
    } label: {
      VStack(spacing: 6) {
        if command == .ok {
          Text("OK").font(.system(size: 28, weight: .bold))
        } else if let digit = RemoteCommand.digits.firstIndex(of: command) {
          Text(String(digit)).font(.system(size: 26, weight: .medium, design: .rounded))
        } else {
          Image(systemName: command.symbol).font(.system(size: 24, weight: .medium))
        }
        if label && command != .ok && !RemoteCommand.digits.contains(command) {
          Text(command.title).font(.caption.weight(.medium)).lineLimit(1).minimumScaleFactor(0.7)
        }
      }.frame(maxWidth: .infinity, maxHeight: .infinity).frame(minWidth: 44, minHeight: 44)
        .foregroundStyle(
          Color(
            rgb: prominent
              ? p.background
              : ([.volumeUp, .volumeDown, .power].contains(command) ? p.accent : p.text))
        ).background(
          transparent ? Color.clear : Color(rgb: prominent ? p.accent : p.surface),
          in: RoundedRectangle(cornerRadius: 22)
        ).scaleEffect(pressed ? 0.94 : 1).opacity(enabled ? 1 : 0.4).contentShape(Rectangle())
    }.buttonStyle(.plain).disabled(!enabled).accessibilityLabel(command.title)
      .background {
        GeometryReader { geometry in
          Color.clear.onAppear { keySize = geometry.size }
            .onChange(of: geometry.size) { _, size in keySize = size }
        }
      }
      .simultaneousGesture(
        DragGesture(minimumDistance: 0).updating($contact) { _, state, _ in state = true }.onChanged
        { value in
          guard enabled, store.canHold(command) else { return }
          guard
            pressContact.remainsInside(
              x: value.location.x, y: value.location.y, width: keySize.width, height: keySize.height
            )
          else {
            cancelPress()
            return
          }
          guard !pressed else { return }
          pressed = true
          haptic()
          store.startHold(command, token: holdToken)
        }.onEnded { _ in
          cancelPress()
          pressContact.reset()
        }
      )
      .onChange(of: contact) { _, active in
        if !active {
          cancelPress()
          pressContact.reset()
        }
      }
      .onChange(of: enabled) { _, active in
        if !active { cancelPress() }
      }
      .onDisappear {
        cancelPress()
        pressContact.reset()
      }
      .accessibilityHint(
        store.nativeHold(command)
          ? L10n.text("Touch and hold for the TV's long-press action.") : ""
      )
      .accessibilityAction { store.dispatch(command) }
  }
  private func cancelPress() {
    store.stopHold(token: holdToken)
    pressed = false
  }
  private func haptic() { UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.6) }
}
struct DPad: View {
  @Environment(\.remotePalette) private var p
  var body: some View {
    GeometryReader { g in
      let side = min(g.size.width, g.size.height)
      let key = max(44, side * 0.25)
      ZStack {
        Circle().fill(
          RadialGradient(
            colors: [Color(rgb: p.raised), Color(rgb: p.surface)], center: .center, startRadius: 0,
            endRadius: side / 2)
        ).overlay(Circle().stroke(Color(rgb: p.outline), lineWidth: 1))
        CommandKey(command: .up, transparent: true).frame(width: key, height: key).offset(
          y: -side * 0.35)
        CommandKey(command: .down, transparent: true).frame(width: key, height: key).offset(
          y: side * 0.35)
        CommandKey(command: .left, transparent: true).frame(width: key, height: key).offset(
          x: -side * 0.35)
        CommandKey(command: .right, transparent: true).frame(width: key, height: key).offset(
          x: side * 0.35)
        CommandKey(command: .ok, prominent: true).clipShape(Circle()).frame(
          width: side * 0.36, height: side * 0.36)
      }.frame(width: side, height: side).position(x: g.size.width / 2, y: g.size.height / 2)
    }
  }
}
struct Rocker: View {
  let kind: CanvasKind
  @Environment(\.remotePalette) private var p
  var body: some View {
    VStack(spacing: 0) {
      CommandKey(command: kind.keys[0], transparent: true)
      Text(kind == .volume ? L10n.text("VOL") : L10n.text("CH")).font(.caption.weight(.medium))
        .foregroundStyle(
          Color(rgb: p.muted)
        ).padding(.vertical, 8)
      CommandKey(command: kind.keys[1], transparent: true)
    }.background(Color(rgb: p.surface), in: Capsule())
  }
}
struct TouchSurface: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.remotePalette) private var p
  @State private var position: CGPoint?
  @State private var anchor: CGSize = .zero
  @State private var last = Date.distantPast
  @State private var travelled = false
  var body: some View {
    ZStack {
      RoundedRectangle(cornerRadius: 34).fill(Color(rgb: p.surface))
      VStack(spacing: 16) {
        Image(systemName: "hand.draw").font(.system(size: 34, weight: .light))
        Text(store.caps.pointer ? L10n.text("Move to point") : L10n.text("Swipe to navigate")).font(
          .subheadline)
        Text(L10n.text("Tap anywhere to select")).font(.caption).foregroundStyle(
          Color(rgb: p.muted))
      }.foregroundStyle(Color(rgb: p.muted))
      if let position {
        Circle().fill(Color(rgb: p.accent).opacity(0.24)).frame(width: 64, height: 64).overlay(
          Circle().stroke(Color(rgb: p.accent), lineWidth: 1)
        ).position(position)
      }
    }
    .contentShape(Rectangle()).gesture(
      DragGesture(minimumDistance: 0).onChanged { value in
        position = value.location
        let dx = value.translation.width - anchor.width
        let dy = value.translation.height - anchor.height
        if store.caps.pointer {
          if abs(dx) + abs(dy) > 2 {
            store.move(dx, dy)
            anchor = value.translation
            travelled = true
          }
        } else if max(abs(dx), abs(dy)) >= (travelled ? 32 : 18),
          Date().timeIntervalSince(last) >= 0.09
        {
          store.dispatch(abs(dx) > abs(dy) ? (dx > 0 ? .right : .left) : (dy > 0 ? .down : .up))
          anchor = value.translation
          last = Date()
          travelled = true
        }
      }.onEnded { _ in
        if !travelled { store.click() }
        position = nil
        anchor = .zero
        travelled = false
      }
    )
    .accessibilityElement(children: .ignore).accessibilityIdentifier("tv-pointer-surface")
    .accessibilityLabel(
      store.caps.pointer
        ? L10n.text("TV touchpad, pointer") : L10n.text("TV touchpad, swipe navigation")
    ).accessibilityAction(named: L10n.text("Select")) { store.click() }.accessibilityAction(
      named: L10n.text("Up")
    ) {
      store.dispatch(.up)
    }.accessibilityAction(named: L10n.text("Down")) { store.dispatch(.down) }.accessibilityAction(
      named: L10n.text("Left")
    ) { store.dispatch(.left) }.accessibilityAction(named: L10n.text("Right")) {
      store.dispatch(.right)
    }
  }
}
struct NumberPad: View {
  @Environment(RemoteStore.self) private var store
  var body: some View {
    VStack(spacing: 6) {
      ForEach(0..<3, id: \.self) { row in
        HStack(spacing: 6) {
          ForEach(1...3, id: \.self) { n in CommandKey(command: RemoteCommand.digits[row * 3 + n]) }
        }
      }
      HStack(spacing: 6) {
        if store.caps.commands.contains(.delete) {
          CommandKey(command: .delete)
        } else {
          Color.clear
        }
        CommandKey(command: .zero)
        if store.caps.commands.contains(.ok) { CommandKey(command: .ok) } else { Color.clear }
      }
    }
  }
}
struct CanvasControl: View {
  let item: CanvasItem
  @Environment(RemoteStore.self) private var store
  @Environment(\.remotePalette) private var p
  var body: some View {
    switch item.kind {
    case .key: if let command = item.command { CommandKey(command: command, label: item.w >= 2) }
    case .dpad: DPad()
    case .touch:
      VStack(spacing: 8) {
        TouchSurface()
        if store.caps.scroll {
          HStack {
            Button(L10n.text("Scroll up"), systemImage: "arrow.up") { store.scroll(-1) }
            Spacer()
            Button(L10n.text("Scroll down"), systemImage: "arrow.down") { store.scroll(1) }
          }.font(.caption).buttonStyle(.bordered).frame(minHeight: 44)
        }
      }
    case .volume, .channels: Rocker(kind: item.kind)
    case .navigation, .playback:
      HStack(spacing: 0) {
        ForEach(item.kind.keys) {
          CommandKey(command: $0, label: item.kind == .navigation, transparent: true)
        }
      }.background(Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 26))
    case .numbers: NumberPad()
    case .keyboard: tile("keyboard", L10n.text("Keyboard")) { store.sheet = .keyboard }
    case .tv: tile("tv", L10n.text("TV")) { store.tvAction() }
    case .app:
      if let app = store.profile.apps.first(where: { $0.target == item.app }) {
        Button {
          store.launch(app)
        } label: {
          HStack(spacing: 10) {
            ShortcutIconView(app: app)
            Text(app.name).font(.caption.weight(.medium))
          }.frame(maxWidth: .infinity, maxHeight: .infinity).background(
            Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(.plain).accessibilityLabel(app.name)
      }
    }
  }
  private func tile(_ symbol: String, _ title: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      VStack(spacing: 6) {
        Image(systemName: symbol).font(.system(size: 26))
        Text(title).font(.caption.weight(.medium))
      }.frame(maxWidth: .infinity, maxHeight: .infinity).frame(minHeight: 48).foregroundStyle(
        Color(rgb: p.text)
      ).background(Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 22))
    }.buttonStyle(.plain).accessibilityLabel(title)
  }
}
struct RemoteCanvasView: View {
  let canvas: RemoteCanvas
  @Environment(RemoteStore.self) private var store
  var body: some View {
    GeometryReader { g in
      let gap: CGFloat = 8
      let width = (g.size.width - 5 * gap) / 6
      let height = CGFloat(
        CanvasFit.rowHeight(
          cell: Double(width), available: Double(g.size.height), rows: canvas.rows, gap: Double(gap)
        ))
      ScrollView {
        ZStack(alignment: .topLeading) {
          ForEach(
            canvas.items.filter {
              $0.supported(store.caps, store.profile.apps)
                && !($0.kind == .tv && store.profile.hideTV)
            }
          ) { item in
            CanvasControl(item: item).frame(
              width: CGFloat(item.w) * width + CGFloat(item.w - 1) * gap,
              height: CGFloat(item.h) * height + CGFloat(item.h - 1) * gap
            ).offset(x: CGFloat(item.x) * (width + gap), y: CGFloat(item.y) * (height + gap))
          }
        }.frame(
          width: g.size.width, height: CGFloat(canvas.rows) * (height + gap) - gap,
          alignment: .topLeading
        ).frame(minHeight: g.size.height - 10, alignment: .bottom).padding(.bottom, 10)
      }.scrollIndicators(.hidden)
    }
  }
}
