import SwiftUI

struct PresetsView: View {
  let profile: TVProfile
  let apply: (TVProfile) -> Void
  @Environment(RemoteStore.self) private var store
  @Environment(\.dismiss) private var dismiss
  @Environment(\.luma) private var p
  @State private var chosen: CanvasTemplate?
  @State private var saved: SavedPreset?
  @State private var saveName = ""
  @State private var naming = false
  @State private var deleting: SavedPreset?
  var body: some View {
    @Bindable var store = store
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 22) {
          Text(L10n.text("Find your familiar.")).font(
            .system(size: 30, weight: .semibold, design: .rounded))
          Text(L10n.text("Same controls. Your way to reach them.")).foregroundStyle(
            Color(rgb: p.muted))
          Picker(L10n.text("Hand preference"), selection: $store.hand) {
            Text(L10n.text("Right hand")).tag(HandPreference.right)
            Text(L10n.text("Left hand")).tag(HandPreference.left)
          }.pickerStyle(.segmented)
          LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 14)], spacing: 14) {
            ForEach(CanvasTemplate.allCases) { t in
              Button {
                chosen = t
                saved = nil
              } label: {
                VStack(alignment: .leading, spacing: 12) {
                  CanvasPreview(
                    canvas: t.create(store.caps, apps: profile.apps, hand: store.hand),
                    apps: profile.apps
                  )
                  .frame(height: 170).padding(8)
                  Text(t.title).font(.subheadline.weight(.semibold))
                  Text(t.detail).font(.caption).foregroundStyle(Color(rgb: p.muted))
                  Text(chosen == t ? L10n.text("Selected") : L10n.text("Preview")).font(.caption)
                    .foregroundStyle(
                      Color(rgb: chosen == t ? p.accent : p.muted))
                }.padding(14).background(
                  Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 24)
                ).overlay(
                  RoundedRectangle(cornerRadius: 24).stroke(
                    chosen == t ? Color(rgb: p.accent) : .clear, lineWidth: 2))
              }.buttonStyle(.plain)
                .accessibilityLabel(L10n.format("Apply %@ preset", t.title))
                .accessibilityIdentifier("preset-\(t.rawValue)")
            }
          }
          if !store.presets.isEmpty {
            Text(L10n.text("Saved by you")).font(.headline)
            ForEach(store.presets) { preset in
              HStack {
                Button {
                  saved = preset
                  chosen = nil
                } label: {
                  HStack {
                    CanvasPreview(canvas: preset.canvas, apps: preset.apps).frame(
                      width: 64, height: 80)
                    Text(preset.name)
                    Spacer()
                    if saved?.id == preset.id { Image(systemName: "checkmark.circle.fill") }
                  }.contentShape(Rectangle())
                }.buttonStyle(.plain)
                Button(role: .destructive) {
                  deleting = preset
                } label: {
                  Image(systemName: "trash").frame(width: 44, height: 48)
                }.accessibilityLabel(L10n.format("Delete %@", preset.name))
              }.padding(14).background(
                Color(rgb: p.surface), in: RoundedRectangle(cornerRadius: 20))
            }
          }
          Button {
            naming = true
          } label: {
            Label(L10n.text("Save current as preset"), systemImage: "plus.square.on.square").frame(
              minHeight: 48)
          }.disabled(store.presets.count >= 12 || profile.canvas?.items.isEmpty != false)
          Text(
            L10n.text(
              "Color stays yours when you change presets. Saved layouts keep their exact positions."
            )
          ).font(.footnote).foregroundStyle(Color(rgb: p.muted))
        }.padding(20)
      }.safeAreaInset(edge: .bottom) {
        PrimaryButton(title: L10n.text("Use layout")) {
          var value = profile
          if let saved {
            value.apps = saved.apps
            value.canvas = RemoteCanvas(
              items: saved.canvas.items.filter { $0.supported(store.caps, saved.apps) })
          } else if let chosen {
            value.canvas = chosen.create(store.caps, apps: profile.apps, hand: store.hand)
          }
          apply(value)
          dismiss()
        }.disabled(chosen == nil && saved == nil).padding(20).background(Color(rgb: p.background))
      }.background(Color(rgb: p.background)).navigationTitle(L10n.text("Presets"))
        .navigationBarTitleDisplayMode(.inline).toolbar {
          ToolbarItem(placement: .topBarLeading) { Button(L10n.text("Cancel")) { dismiss() } }
        }
    }
    .alert(L10n.text("Save preset"), isPresented: $naming) {
      TextField(L10n.text("Name"), text: $saveName)
      Button(L10n.text("Save")) {
        let name = saveName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty, name.count <= 32, store.presets.count < 12, let c = profile.canvas,
          c.valid
        {
          store.presets.append(SavedPreset(name: name, canvas: c, apps: profile.apps))
          store.persist()
        }
        saveName = ""
      }
      .disabled(
        saveName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || saveName.count > 32
          || store.presets.contains {
            $0.name.caseInsensitiveCompare(saveName.trimmingCharacters(in: .whitespacesAndNewlines))
              == .orderedSame
          })
      Button(L10n.text("Cancel"), role: .cancel) {}
    } message: {
      Text(L10n.text("Up to 12 layouts. Color and pairing are stored separately."))
    }
    .confirmationDialog(
      L10n.text("Delete saved preset?"),
      isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
      titleVisibility: .visible
    ) {
      Button(L10n.text("Delete"), role: .destructive) {
        if let deleting {
          store.presets.removeAll { $0.id == deleting.id }
          store.persist()
        }
        deleting = nil
      }
    }
  }
}
