import CoreMotion
import Observation
import SwiftUI

@MainActor @Observable final class MotionSession {
  var active = false
  var available = false
  var sensitivity = 1.0
  private let motion = CMMotionManager()
  private var accumulatedX = 0.0
  private var accumulatedY = 0.0
  private var last = 0.0
  private var lastKey = 0.0
  init() { available = motion.isDeviceMotionAvailable }
  func start(store: RemoteStore) {
    guard available, !active else { return }
    active = true
    last = 0
    lastKey = 0
    accumulatedX = 0
    accumulatedY = 0
    motion.deviceMotionUpdateInterval = 0.02
    motion.startDeviceMotionUpdates(to: .main) { [weak self, weak store] sample, _ in
      Task { @MainActor in
        guard let self, self.active, let store, store.connection.phase == .ready, let sample else {
          self?.stop()
          return
        }
        let dt = self.last == 0 ? 0 : min(0.05, sample.timestamp - self.last)
        self.last = sample.timestamp
        let gravity = sample.gravity
        let rate = sample.rotationRate
        let upright = abs(gravity.y) > abs(gravity.z)
        let dx = -(upright ? rate.z : rate.y)
        let dy = -rate.x
        if store.caps.pointer {
          let x = abs(dx) < 0.035 ? 0 : dx * dt * 700 * self.sensitivity
          let y = abs(dy) < 0.035 ? 0 : dy * dt * 700 * self.sensitivity
          if abs(x) + abs(y) > 0.2 { store.move(x.clamped(to: -40...40), y.clamped(to: -40...40)) }
        } else if store.caps.navigation {
          self.accumulatedX += dx * dt * self.sensitivity
          self.accumulatedY += dy * dt * self.sensitivity
          if max(abs(self.accumulatedX), abs(self.accumulatedY)) > 0.12,
            sample.timestamp - self.lastKey > 0.18
          {
            store.dispatch(
              abs(self.accumulatedX) > abs(self.accumulatedY)
                ? (self.accumulatedX > 0 ? .right : .left) : (self.accumulatedY > 0 ? .down : .up))
            self.accumulatedX = 0
            self.accumulatedY = 0
            self.lastKey = sample.timestamp
          }
        }
      }
    }
  }
  func stop() {
    active = false
    motion.stopDeviceMotionUpdates()
    accumulatedX = 0
    accumulatedY = 0
    last = 0
  }
}
struct MotionView: View {
  @Environment(RemoteStore.self) private var store
  @Environment(\.luma) private var p
  @Environment(\.scenePhase) private var phase
  @State private var motion = MotionSession()
  var body: some View {
    @Bindable var motion = motion
    Panel(title: L10n.text("Motion remote")) {
      VStack(spacing: 26) {
        Image(systemName: "move.3d").font(.system(size: 55, weight: .ultraLight)).foregroundStyle(
          Color(rgb: p.accent))
        Text(
          store.caps.pointer
            ? L10n.text("Point with a little movement.")
            : L10n.text("Navigate with a little movement.")
        ).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
        Text(
          store.caps.pointer
            ? L10n.text("Hold below and move your phone gently. Release to recenter.")
            : L10n.text(
              "This connection supports arrows, not a TV cursor. Hold below and tilt to move between items."
            )
        ).foregroundStyle(Color(rgb: p.muted)).multilineTextAlignment(.center)
        if !motion.available {
          Text(
            L10n.text(
              "Motion needs a physical iPhone with motion sensors. Simulator sensors are unavailable."
            )
          ).font(.footnote).foregroundStyle(Color(rgb: p.muted))
        }
        Text(motion.active ? L10n.text("Moving…") : L10n.text("Hold to move")).font(
          .title3.weight(.semibold)
        ).frame(
          maxWidth: .infinity
        ).frame(height: 160).foregroundStyle(Color(rgb: motion.active ? p.background : p.accent))
          .background(
            Color(rgb: motion.active ? p.accent : p.surface), in: RoundedRectangle(cornerRadius: 36)
          ).gesture(
            DragGesture(minimumDistance: 0).onChanged { _ in motion.start(store: store) }.onEnded {
              _ in motion.stop()
            }
          ).accessibilityAddTraits(.isButton).accessibilityHint(
            L10n.text("Touch and hold while moving the phone; release to stop"))
        HStack {
          Text(L10n.text("Sensitivity"))
          Slider(value: $motion.sensitivity, in: 0.4...2)
        }
        Spacer()
      }.padding(26)
    }.onDisappear { motion.stop() }.onChange(of: phase) { _, value in
      if value != .active { motion.stop() }
    }.onChange(of: store.connection.phase) { _, value in if value != .ready { motion.stop() } }
      .onChange(of: store.selected?.id) { _, _ in motion.stop() }
      .onChange(of: store.caps) { _, _ in motion.stop() }
  }
}
