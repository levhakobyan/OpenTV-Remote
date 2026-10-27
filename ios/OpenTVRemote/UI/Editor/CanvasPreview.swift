import SwiftUI

/// Decorative thumbnails share Android's primitive renderer; editing keeps real names and icons below.
struct CanvasPreview: View {
  let canvas: RemoteCanvas
  var apps: [AppShortcut] = AppShortcut.catalog
  @Environment(\.remotePalette) private var p
  var body: some View {
    Canvas { context, size in
      let unit = min(size.width / 6.4, size.height / (CGFloat(canvas.rows) + 0.4))
      let origin = CGPoint(
        x: (size.width - unit * 6) / 2,
        y: (size.height - unit * CGFloat(canvas.rows)) / 2)
      let gap = unit * 0.09
      let raised = Color(rgb: p.raised)
      let muted = Color(rgb: p.muted)
      let accent = Color(rgb: p.accent)
      func circle(_ color: Color, _ radius: CGFloat, _ center: CGPoint) {
        context.fill(
          Path(
            ellipseIn: CGRect(
              x: center.x - radius, y: center.y - radius,
              width: radius * 2, height: radius * 2)), with: .color(color))
      }
      func rounded(_ color: Color, _ rect: CGRect, _ radius: CGFloat) {
        context.fill(Path(roundedRect: rect, cornerRadius: radius), with: .color(color))
      }
      func line(_ start: CGPoint, _ end: CGPoint, _ width: CGFloat) {
        var path = Path()
        path.move(to: start)
        path.addLine(to: end)
        context.stroke(path, with: .color(muted), lineWidth: width)
      }
      for item in canvas.items {
        let top = CGPoint(
          x: origin.x + CGFloat(item.x) * unit + gap,
          y: origin.y + CGFloat(item.y) * unit + gap)
        let box = CGSize(
          width: CGFloat(item.w) * unit - gap * 2,
          height: CGFloat(item.h) * unit - gap * 2)
        let center = CGPoint(x: top.x + box.width / 2, y: top.y + box.height / 2)
        let rect = CGRect(origin: top, size: box)
        switch item.kind {
        case .dpad:
          let radius = min(box.width, box.height) / 2
          let outline = Path(
            ellipseIn: CGRect(
              x: center.x - radius, y: center.y - radius,
              width: radius * 2, height: radius * 2))
          context.fill(outline, with: .color(raised))
          context.stroke(outline, with: .color(muted.opacity(0.6)), lineWidth: unit * 0.04)
          circle(accent, radius * 0.34, center)
          for offset in [
            CGPoint(x: 0, y: -0.7), CGPoint(x: 0, y: 0.7),
            CGPoint(x: -0.7, y: 0), CGPoint(x: 0.7, y: 0),
          ] {
            circle(
              muted, unit * 0.10,
              CGPoint(
                x: center.x + offset.x * radius,
                y: center.y + offset.y * radius))
          }
        case .touch:
          rounded(raised, rect, unit * 0.35)
          circle(accent, unit * 0.38, center)
          for x in 1...3 {
            for y in 1...3 {
              circle(
                muted.opacity(0.35), unit * 0.025,
                CGPoint(
                  x: top.x + box.width * CGFloat(x) / 4,
                  y: top.y + box.height * CGFloat(y) / 4))
            }
          }
        case .numbers:
          rounded(Color(rgb: p.background), rect, unit * 0.25)
          for x in 0..<3 {
            for y in 0..<4 {
              rounded(
                raised,
                CGRect(
                  x: top.x + box.width * CGFloat(x) / 3 + gap,
                  y: top.y + box.height * CGFloat(y) / 4 + gap,
                  width: box.width / 3 - gap * 2, height: box.height / 4 - gap * 2),
                unit * 0.12)
            }
          }
        case .volume, .channels:
          rounded(raised, rect, unit * 0.4)
          if item.kind == .volume {
            for y in [0.24, 0.76] {
              let speaker = CGPoint(x: top.x + box.width * 0.42, y: top.y + box.height * y)
              var path = Path()
              for (index, point) in [
                CGPoint(x: -0.20, y: -0.10), CGPoint(x: -0.10, y: -0.10),
                CGPoint(x: 0.05, y: -0.24), CGPoint(x: 0.05, y: 0.24),
                CGPoint(x: -0.10, y: 0.10), CGPoint(x: -0.20, y: 0.10),
              ].enumerated() {
                let target = CGPoint(x: speaker.x + unit * point.x, y: speaker.y + unit * point.y)
                if index == 0 { path.move(to: target) } else { path.addLine(to: target) }
              }
              path.closeSubpath()
              context.fill(path, with: .color(accent))
              var arc = Path()
              for step in 0...20 {
                let angle = (-55 + 110 * Double(step) / 20) * .pi / 180
                let point = CGPoint(
                  x: speaker.x + unit * (0.16 + 0.20 * cos(angle)),
                  y: speaker.y + unit * 0.20 * sin(angle))
                if step == 0 { arc.move(to: point) } else { arc.addLine(to: point) }
              }
              context.stroke(arc, with: .color(accent), lineWidth: unit * 0.06)
            }
          } else {
            for y in [0.24, 0.76] {
              line(
                CGPoint(x: top.x + box.width * 0.3, y: top.y + box.height * y),
                CGPoint(x: top.x + box.width * 0.7, y: top.y + box.height * y), unit * 0.08)
            }
            line(
              CGPoint(x: top.x + box.width / 2, y: top.y + box.height * 0.17),
              CGPoint(x: top.x + box.width / 2, y: top.y + box.height * 0.31), unit * 0.08)
          }
        case .playback, .navigation:
          rounded(raised, rect, unit * 0.25)
          for (index, command) in item.kind.keys.enumerated() {
            circle(
              command == .playPause ? accent : muted, unit * 0.12,
              CGPoint(
                x: top.x + box.width * (CGFloat(index) + 0.5) / CGFloat(item.kind.keys.count),
                y: top.y + box.height / 2))
          }
        default:
          let selected = item.kind == .app || item.command == .ok || item.command == .playPause
          rounded(selected ? accent : raised, rect, unit * 0.23)
          if item.kind == .keyboard {
            for index in 1...3 {
              circle(
                muted, unit * 0.06,
                CGPoint(
                  x: center.x + CGFloat(index - 2) * unit * 0.25,
                  y: center.y))
            }
          } else if !selected {
            circle(muted, unit * 0.065, center)
          }
        }
      }
    }.accessibilityHidden(true)
  }
}
struct EditorItemPreview: View {
  let item: CanvasItem
  var app: AppShortcut? = nil
  @Environment(\.remotePalette) private var p
  var body: some View {
    Group {
      if item.kind == .dpad {
        PadIllustration()
      } else if item.kind.vertical {
        VStack {
          Image(systemName: item.kind == .volume ? "speaker.wave.3" : "plus")
          Spacer()
          Text(item.kind == .volume ? L10n.text("VOL") : L10n.text("CH")).font(.caption)
          Spacer()
          Image(systemName: item.kind == .volume ? "speaker.wave.1" : "minus")
        }.padding(.vertical, 20).frame(maxWidth: .infinity, maxHeight: .infinity).foregroundStyle(
          Color(rgb: item.kind == .volume ? p.accent : p.text)
        ).background(Color(rgb: p.surface), in: Capsule())
      } else {
        VStack(spacing: 8) {
          if item.kind == .app, let app {
            ShortcutIconView(app: app)
          } else if let c = item.command, let digit = RemoteCommand.digits.firstIndex(of: c) {
            Text(String(digit)).font(.title)
          } else {
            Image(systemName: item.command?.symbol ?? item.kind.symbol).font(.title2)
          }
          if item.w > 1 {
            Text(app?.name ?? item.command?.title ?? item.kind.title).font(.caption).lineLimit(1)
          }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(
          Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 20))
      }
    }.foregroundStyle(Color(rgb: p.text))
  }
}
private struct PadIllustration: View {
  @Environment(\.remotePalette) private var p
  var body: some View {
    GeometryReader { geometry in
      let side = min(geometry.size.width, geometry.size.height)
      ZStack {
        Circle().fill(
          RadialGradient(
            colors: [Color(rgb: p.raised), Color(rgb: p.surface)], center: .center, startRadius: 0,
            endRadius: side / 2)
        ).overlay(Circle().stroke(Color(rgb: p.outline), lineWidth: side > 100 ? 1 : 0.5))
        Image(systemName: "chevron.up").offset(y: -side * 0.35)
        Image(systemName: "chevron.down").offset(y: side * 0.35)
        Image(systemName: "chevron.left").offset(x: -side * 0.35)
        Image(systemName: "chevron.right").offset(x: side * 0.35)
        Circle().fill(Color(rgb: p.accent)).frame(width: side * 0.36, height: side * 0.36).overlay(
          Text("OK").font(.system(size: max(5, side * 0.13), weight: .bold)).foregroundStyle(
            Color(rgb: p.background)))
      }.font(.system(size: max(5, side * 0.09), weight: .semibold)).foregroundStyle(
        Color(rgb: p.text)
      ).frame(width: side, height: side).position(
        x: geometry.size.width / 2, y: geometry.size.height / 2)
    }
  }
}
