# Built-in remote preset contract

`canvas-template-fixtures.json` records the eight built-in presets in their user-visible order, English title and description, capability inputs, two app targets, and normalized **runtime-generated** geometry. Both platform unit suites load this one file and exercise their real template generators. Saved user layouts are not read, migrated or overwritten.

The matrix covers 7 capability profiles × 0/1/2 apps × both hands × 8 templates = **336 layouts**. Full, negotiated Android feature mask 622, partial commands, navigation only, pointer only, apps only and empty capabilities exercise grouping, individual-key fallback, omitted controls, app slots and empty-row removal. Geometry includes row count, control kind, x/y/width/height, command Android key code and app target. UUIDs are deliberately excluded. Items sort by row, column and kind; the normalization preserves every geometry and action value.

To export both actual runtimes, run from the repository root (use the repository's configured Java environment for Gradle):

```sh
(cd android && LUMA_CANVAS_SNAPSHOT_OUTPUT=/private/tmp/luma-android-canvas.json ./gradlew :app:testDebugUnitTest --tests com.luma.remote.protocol.CanvasPresetsTest.builtInTemplatesMatchSharedCrossPlatformFixtures --rerun-tasks)
CLANG_MODULE_CACHE_PATH=/private/tmp/luma-swift-module-cache SWIFTPM_MODULECACHE_OVERRIDE=/private/tmp/luma-swift-module-cache LUMA_CANVAS_SNAPSHOT_OUTPUT=/private/tmp/luma-ios-canvas.json swift test --disable-sandbox --package-path ios --scratch-path /private/tmp/luma-ios-core-build --filter LumaCoreTests.testBuiltInTemplatesMatchSharedCrossPlatformFixtures
python3 tools/check_canvas_preset_parity.py --android /private/tmp/luma-android-canvas.json --ios /private/tmp/luma-ios-canvas.json
```

`--disable-sandbox` allows SwiftPM to run inside an existing development sandbox; it is unnecessary outside that environment. Native tests export before comparing fixtures, so an intentional preset change may initially fail fixture assertions. Review both runtime outputs, then use the verifier's `--update` flag to accept a new shared fixture **only if both platforms match**. Rerun both native tests without failures afterward. Never update fixtures merely to hide an unintended regression. The verifier compares outputs and never contains a second layout algorithm.

Template enum raw values and saved-preset encodings remain platform-specific persistence contracts. `android`/`ios` fixture identifiers map those established enum cases; command key codes provide an explicit action mapping without changing either platform's enum names.
