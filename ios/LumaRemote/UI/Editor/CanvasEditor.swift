import SwiftUI

private enum EditorPanel: String, Identifiable {
  case picker, presets, size
  var id: String { rawValue }
}
struct CanvasEditor: View {
  @State var profile: TVProfile
  @Environment(RemoteStore.self) private var store
  @Environment(\.dismiss) private var dismiss
  @Environment(\.luma) private var p
  @State private var selected: String?
  @State private var panel: EditorPanel?
  @State private var history = [TVProfile]()
  @State private var resizing = false
  private var canvas: RemoteCanvas { profile.canvas ?? RemoteCanvas() }
  private var selectedItem: CanvasItem? { canvas.items.first { $0.id == selected } }
  var body: some View {
    NavigationStack {
      VStack(spacing: 0) {
        HStack {
          Text(L10n.text("Drag to move · tap for options")).font(.caption).foregroundStyle(
            Color(rgb: p.muted))
          Spacer(minLength: 4)
          Button {
            if let previous = history.popLast() {
              profile = previous
              selected = nil
              resizing = false
            }
          } label: {
            Image(systemName: "arrow.uturn.backward").frame(width: 44, height: 44)
          }.disabled(history.isEmpty).accessibilityLabel(L10n.text("Undo layout change"))
          Button(L10n.text("Presets")) { panel = .presets }
        }.padding(.horizontal, 16)
        EditorDragBoard(
          canvas: canvas, apps: profile.apps, selected: $selected, resizing: $resizing,
          apply: commit)
        // Selection changes content, never the viewport height or the grid's touch coordinates.
        HStack(spacing: 8) {
          Button {
            panel = .picker
          } label: {
            if selectedItem == nil {
              Label(L10n.text("Add controls"), systemImage: "plus")
            } else {
              Image(systemName: "plus").frame(width: 44, height: 44)
            }
          }.accessibilityLabel(L10n.text("Add controls"))
          ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
              if let item = selectedItem {
                Button(
                  resizing ? L10n.text("Move") : L10n.text("Resize"),
                  systemImage: resizing ? "move.3d" : "arrow.up.left.and.arrow.down.right"
                ) { resizing.toggle() }
                Button("\(item.w) × \(item.h)") { panel = .size }
                if !item.kind.keys.isEmpty {
                  Button(L10n.text("Split")) {
                    if let next = canvas.splitting(item.id) {
                      commit(next)
                      resizing = false
                    }
                  }
                }
                if canvas.joining(item.id) != nil {
                  Button(L10n.text("Join")) {
                    if let next = canvas.joining(item.id) {
                      commit(next)
                      resizing = false
                    }
                  }
                }
                Button(L10n.text("Remove"), systemImage: "trash", role: .destructive) {
                  commit(
                    RemoteCanvas(items: canvas.items.filter { $0.id != item.id })
                      .removingEmptyRows())
                  selected = nil
                  resizing = false
                }
              } else if canvas.emptyRows > 0 {
                Button(L10n.text("Remove empty rows")) { commit(canvas.removingEmptyRows()) }.font(
                  .caption)
              }
            }.buttonStyle(.bordered).controlSize(.small)
          }
        }.frame(height: 56).padding(.horizontal, 16).background(Color(rgb: p.background))
      }.background(Color(rgb: p.background)).navigationTitle(L10n.text("Customize remote"))
        .navigationBarTitleDisplayMode(.inline).toolbar {
          ToolbarItem(placement: .topBarLeading) { Button(L10n.text("Cancel")) { dismiss() } }
          ToolbarItem(placement: .topBarTrailing) {
            Button(L10n.text("Done")) {
              store.saveProfile(profile)
              dismiss()
            }.fontWeight(.semibold)
          }
        }
    }
    .onAppear { if profile.canvas == nil { profile.canvas = store.canvas } }
    .sheet(item: $panel) { panel in
      switch panel {
      case .presets:
        PresetsView(profile: profile) { next in
          history.append(profile)
          profile = next
          selected = nil
          resizing = false
        }
      case .picker:
        ControlPicker(profile: $profile) { item in
          if let next = canvas.adding(item) {
            commit(next)
            selected = item.id
          } else {
            store.message = L10n.text(
              "There’s no room for that control. Remove or resize an item first.")
          }
        }
      case .size:
        if let item = selectedItem {
          ExactSizeView(item: item) { w, h in
            if let next = canvas.resizing(item.id, w: w, h: h) {
              commit(next)
              return true
            }
            return false
          }
        }
      }
    }
  }
  private func commit(_ next: RemoteCanvas) {
    guard next.valid else { return }
    history.append(profile)
    if history.count > 30 { history.removeFirst() }
    profile.canvas = next
  }
}

private struct BoardTopKey: PreferenceKey {
  static var defaultValue: CGFloat = 0
  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// Pure editing surface: direct drags have no dependency on a selected TV or command sender.
private struct EditorDragBoard: View {
  let canvas: RemoteCanvas
  let apps: [AppShortcut]
  @Binding var selected: String?
  @Binding var resizing: Bool
  let apply: (RemoteCanvas) -> Void
  @Environment(\.luma) private var p
  @Environment(\.scenePhase) private var phase
  @GestureState private var contact = false
  @State private var base: RemoteCanvas?
  @State private var preview: RemoteCanvas?
  @State private var activeID: String?
  @State private var moving = false
  @State private var translation: CGSize = .zero
  @State private var destination: CanvasItem?
  @State private var invalid = false
  @State private var boardTop: CGFloat = 0
  @State private var lastScroll = Date.distantPast
  private var shown: RemoteCanvas { preview ?? canvas }
  var body: some View {
    GeometryReader { geometry in
      let gap: CGFloat = 8
      let cell = (geometry.size.width - 32 - 5 * gap) / 6
      let row: CGFloat = 66
      let rows = min(24, max(6, shown.rows + 2))
      ScrollViewReader { scroll in
        ScrollView {
          ZStack(alignment: .topLeading) {
            ForEach(0..<rows, id: \.self) { y in
              ForEach(0..<6, id: \.self) { x in
                Button {
                  if let selected, let next = canvas.placing(selected, x: x, y: y) { apply(next) }
                } label: {
                  RoundedRectangle(cornerRadius: 10).stroke(
                    Color(rgb: p.outline).opacity(0.18),
                    style: StrokeStyle(lineWidth: 1, dash: [3, 4])
                  ).frame(width: cell, height: row)
                }.offset(x: CGFloat(x) * (cell + gap), y: CGFloat(y) * (row + gap))
                  .accessibilityLabel(L10n.format("Grid column %ld, row %ld", x + 1, y + 1))
              }
            }
            if let destination {
              RoundedRectangle(cornerRadius: 22)
                .fill(Color(rgb: invalid ? 0xFFB4AB : p.accent).opacity(0.12))
                .overlay(
                  RoundedRectangle(cornerRadius: 22).stroke(
                    Color(rgb: invalid ? 0xFFB4AB : p.accent),
                    style: StrokeStyle(lineWidth: 2, dash: invalid ? [5, 4] : []))
                )
                .overlay(alignment: .topTrailing) {
                  Image(systemName: invalid ? "xmark.circle.fill" : "checkmark.circle.fill")
                    .padding(5).foregroundStyle(Color(rgb: invalid ? 0xFFB4AB : p.accent))
                }
                .frame(
                  width: CGFloat(destination.w) * (cell + gap) - gap,
                  height: CGFloat(destination.h) * (row + gap) - gap
                )
                .offset(
                  x: CGFloat(destination.x) * (cell + gap), y: CGFloat(destination.y) * (row + gap)
                )
                .allowsHitTesting(false)
            }
            // Stable source items keep the recognizer attached while neighbors preview new positions.
            ForEach(canvas.items) { original in
              let visual = shown.items.first { $0.id == original.id } ?? original
              let floating = activeID == original.id && moving
              let origin = floating ? original : visual
              EditorItemPreview(item: visual, app: apps.first { $0.target == original.app })
                .frame(
                  width: CGFloat(visual.w) * (cell + gap) - gap,
                  height: CGFloat(visual.h) * (row + gap) - gap
                )
                .background(
                  Color(rgb: p.background).opacity(floating ? 0.9 : 0),
                  in: RoundedRectangle(cornerRadius: 22)
                )
                .overlay(
                  RoundedRectangle(cornerRadius: 22).stroke(
                    original.id == selected ? Color(rgb: invalid ? 0xFFB4AB : p.accent) : .clear,
                    lineWidth: 2)
                )
                .overlay(alignment: .bottomTrailing) {
                  if original.id == selected && resizing {
                    Image(systemName: "arrow.down.right").font(.headline).foregroundStyle(
                      Color(rgb: p.background)
                    ).frame(width: 40, height: 40).background(Color(rgb: p.accent), in: Circle())
                      .offset(x: 3, y: 3)
                      .highPriorityGesture(
                        DragGesture(minimumDistance: 1, coordinateSpace: .named("editor-board"))
                          .updating($contact) { _, state, _ in state = true }
                          .onChanged { value in
                            updateResize(
                              original, value.translation, cell: cell, row: row, gap: gap)
                          }
                          .onEnded { value in
                            updateResize(
                              original, value.translation, cell: cell, row: row, gap: gap)
                            finish()
                          }
                      )
                      .accessibilityLabel(
                        L10n.format("Resize %@", original.command?.title ?? original.kind.title))
                  }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                  guard base == nil else { return }
                  selected = original.id
                  resizing = false
                }
                .highPriorityGesture(
                  DragGesture(minimumDistance: 4, coordinateSpace: .named("editor-board"))
                    .updating($contact) { _, state, _ in state = true }
                    .onChanged { value in
                      updateMove(original, value.translation, cell: cell, row: row, gap: gap)
                      if let destination, value.location.y + boardTop > geometry.size.height - 48,
                        destination.y + destination.h >= rows - 2,
                        Date().timeIntervalSince(lastScroll) > 0.2
                      {
                        lastScroll = Date()
                        withAnimation(.easeOut(duration: 0.15)) {
                          scroll.scrollTo("editor-bottom", anchor: .bottom)
                        }
                      }
                    }
                    .onEnded { value in
                      updateMove(original, value.translation, cell: cell, row: row, gap: gap)
                      finish()
                    },
                  including: resizing && selected == original.id ? .subviews : .all
                )
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                  L10n.format(
                    "%@, column %ld, row %ld",
                    apps.first { $0.target == original.app }?.name ?? original.command?.title
                      ?? original.kind.title, visual.x + 1, visual.y + 1)
                )
                .accessibilityAddTraits(.isButton)
                .accessibilityAction {
                  selected = original.id
                  resizing = false
                }
                .accessibilityAction(named: L10n.text("Move down")) {
                  moveAccessible(original, dx: 0, dy: 1)
                }
                .accessibilityAction(named: L10n.text("Move up")) {
                  moveAccessible(original, dx: 0, dy: -1)
                }
                .accessibilityAction(named: L10n.text("Move left")) {
                  moveAccessible(original, dx: -1, dy: 0)
                }
                .accessibilityAction(named: L10n.text("Move right")) {
                  moveAccessible(original, dx: 1, dy: 0)
                }
                .offset(
                  x: CGFloat(origin.x) * (cell + gap) + (floating ? translation.width : 0),
                  y: CGFloat(origin.y) * (row + gap) + (floating ? translation.height : 0)
                )
                .shadow(
                  color: .black.opacity(floating ? 0.24 : 0), radius: floating ? 12 : 0,
                  y: floating ? 6 : 0
                )
                .zIndex(floating ? 2 : 1)
            }
          }.frame(
            width: geometry.size.width - 32, height: CGFloat(rows) * (row + gap),
            alignment: .topLeading
          )
          .coordinateSpace(name: "editor-board")
          .background(
            GeometryReader { board in
              Color.clear.preference(
                key: BoardTopKey.self, value: board.frame(in: .named("editor-viewport")).minY)
            }
          )
          .padding(.horizontal, 16)
          Color.clear.frame(height: 1).id("editor-bottom")
        }.scrollIndicators(.hidden).coordinateSpace(name: "editor-viewport")
          .onPreferenceChange(BoardTopKey.self) { boardTop = $0 }
          .scrollDisabled(base != nil)
      }
    }
    .onChange(of: contact) { _, active in if !active && base != nil { clear() } }
    .onChange(of: phase) { _, value in if value != .active { clear() } }
    .onDisappear { clear() }
  }
  private func begin(_ item: CanvasItem, moving: Bool) {
    if base == nil {
      base = canvas
      activeID = item.id
      selected = item.id
      self.moving = moving
      UISelectionFeedbackGenerator().selectionChanged()
    }
  }
  private func updateMove(
    _ item: CanvasItem, _ delta: CGSize, cell: CGFloat, row: CGFloat, gap: CGFloat
  ) {
    begin(item, moving: true)
    resizing = false
    translation = delta
    let source = base ?? canvas
    let original = source.items.first { $0.id == item.id } ?? item
    var target = original
    target.x += Int((delta.width / (cell + gap)).rounded())
    target.y += Int((delta.height / (row + gap)).rounded())
    destination = target
    preview = source.placing(item.id, x: target.x, y: target.y)
    invalid = preview == nil
  }
  private func updateResize(
    _ item: CanvasItem, _ delta: CGSize, cell: CGFloat, row: CGFloat, gap: CGFloat
  ) {
    begin(item, moving: false)
    let source = base ?? canvas
    let original = source.items.first { $0.id == item.id } ?? item
    var target = original
    target.w = max(item.kind.minW, original.w + Int((delta.width / (cell + gap)).rounded()))
    target.h = max(item.kind.minH, original.h + Int((delta.height / (row + gap)).rounded()))
    destination = target
    preview = source.resizing(item.id, w: target.w, h: target.h)
    invalid = preview == nil
  }
  private func finish() {
    if let next = preview, next != canvas { apply(next) }
    clear()
  }
  private func clear() {
    base = nil
    preview = nil
    activeID = nil
    destination = nil
    translation = .zero
    invalid = false
    moving = false
  }
  private func moveAccessible(_ item: CanvasItem, dx: Int, dy: Int) {
    if let next = canvas.placing(item.id, x: item.x + dx, y: item.y + dy) { apply(next) }
  }
}

struct ExactSizeView: View {
  let item: CanvasItem
  let apply: (Int, Int) -> Bool
  @Environment(\.dismiss) private var dismiss
  @State private var w = 1
  @State private var h = 1
  @State private var error = false
  var body: some View {
    Panel(title: L10n.text("Control size")) {
      Form {
        Stepper(L10n.format("Width: %ld", w), value: $w, in: item.kind.minW...6)
          .accessibilityIdentifier(
            "control-width")
        Stepper(L10n.format("Height: %ld", h), value: $h, in: item.kind.minH...24)
          .accessibilityIdentifier(
            "control-height")
        if error {
          Text(L10n.text("That size would overlap a control above it or exceed the grid."))
            .foregroundStyle(
              .red)
        }
        Button(L10n.text("Apply size")) { if apply(w, h) { dismiss() } else { error = true } }
      }.scrollContentBackground(.hidden)
    }.onAppear {
      w = item.w
      h = item.h
    }
  }
}
struct ControlPicker: View {
  @Binding var profile: TVProfile
  let add: (CanvasItem) -> Void
  @Environment(RemoteStore.self) private var store
  @Environment(\.luma) private var p
  @State private var category = 0
  @State private var query = ""
  var body: some View {
    Panel(title: L10n.text("Add controls")) {
      ScrollView {
        VStack(spacing: 18) {
          Picker(L10n.text("Category"), selection: $category) {
            Text(L10n.text("Widgets")).tag(0)
            Text(L10n.text("Buttons")).tag(1)
            if store.caps.apps { Text(L10n.text("Apps")).tag(2) }
          }.pickerStyle(.segmented)
          LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 12)], spacing: 12) {
            if category == 0 {
              ForEach(
                CanvasKind.allCases.filter {
                  $0 != .key && $0 != .app
                    && (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query))
                }
              ) { kind in
                let item = CanvasItem(kind: kind, x: 0, y: 0)
                if item.supported(store.caps, profile.apps) {
                  card(kind.title) {
                    add(item)
                  } content: {
                    EditorItemPreview(item: item).frame(
                      width: kind.vertical ? 48 : 100,
                      height: kind == .dpad || kind == .touch || kind.vertical ? 100 : 70)
                  }
                }
              }
            }
            if category == 1 {
              ForEach(
                RemoteCommand.allCases.filter {
                  store.caps.commands.contains($0)
                    && (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query))
                }
              ) { c in
                card(c.title) {
                  add(CanvasItem(kind: .key, x: 0, y: 0, command: c))
                } content: {
                  if let n = RemoteCommand.digits.firstIndex(of: c) {
                    Text(String(n)).font(.largeTitle)
                  } else {
                    Image(systemName: c.symbol).font(.largeTitle)
                  }
                }
              }
            }
            if category == 2 {
              ForEach(profile.apps) { app in
                card(app.name) {
                  add(CanvasItem(kind: .app, x: 0, y: 0, app: app.target))
                } content: {
                  ShortcutIconView(app: app)
                }
              }
            }
          }
          if category == 2 { AppsConfiguration(profile: $profile) }
        }.padding(18)
      }.searchable(text: $query, prompt: L10n.text("Find a control"))
    }
  }
  private func card<Content: View>(
    _ title: String, action: @escaping () -> Void, @ViewBuilder content: () -> Content
  ) -> some View {
    Button(action: action) {
      VStack(spacing: 12) {
        content().frame(height: 104)
        Text(title).font(.caption.weight(.medium))
      }.frame(maxWidth: .infinity).padding(14).background(
        Color(rgb: p.raised), in: RoundedRectangle(cornerRadius: 22))
    }.buttonStyle(.plain).accessibilityLabel(L10n.format("Add %@", title))
  }
}
