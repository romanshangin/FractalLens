# FractalLens — matte icon

Owner-selected Mandelbrot: flat `#103B75` silhouette on `#D9EDF8`, no highlights,
glow, texture, volume or gradients. Separate square native silhouette/background
layers, no baked corner rounding.

- `matte-square.png`: saved built-in imagegen edit.
- `mandelbrot.png`: transparent silhouette, 1024×1024.
- `flat-square.png`: flat unmasked composition, 1024×1024.
- `FractalLens.icon`: created/verified in Apple Icon Composer; layer effects,
  group highlights, translucency/shadows off. macOS supplies corner/background styling.
- `macos-legacy-mask.png`: Xcode-export alpha mask for Java Dock/ICNS compatibility only.

Flat PNG/ICNS (no painted highlights):
`src/main/resources/com/shangin/fractal/app/icons/`. Maven/shared IntelliJ pass
ICNS via `-Xdock:icon` before JavaFX initializes.

## Rebuild

From repository root, with Pillow installed:

```sh
python3 scripts/packaging/build_app_icon.py
```

Compile native artwork with Xcode:

```sh
mkdir -p target/native-icon
xcrun actool design/app-icon/FractalLens.icon \
  --compile target/native-icon --platform macosx \
  --minimum-deployment-target 13.0 --app-icon FractalLens \
  --output-partial-info-plist target/native-icon/icon-info.plist \
  --output-format human-readable-text
```

Native export: `Assets.car`, `FractalLens.icns`, Info.plist keys. Layered `.app`
needs both resources plus `CFBundleIconName=FractalLens`; `.icon` in JAR alone is
insufficient. Maven/IDE JavaFX uses compatible PNG/ICNS; ordinary
`jpackage --icon src/main/resources/com/shangin/fractal/app/icons/FractalLens.icns`
is also supported.

## Validation

The project was opened in Icon Composer, compiled with `actool`, and inspected
in the native export. The Dock icon was read before and after it was set through
the Java Taskbar API; both images matched the selected Mandelbrot artwork.
The silhouette retains transparent margins; the composition background is opaque.

## Sources and provenance

- [Apple: App icons](https://developer.apple.com/design/human-interface-guidelines/app-icons)
- [Apple: Creating your app icon using Icon Composer](https://developer.apple.com/documentation/xcode/creating-your-app-icon-using-icon-composer)
- [Java: macOS options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#extra-options-for-macos)

Built-in imagegen edit; alpha/layer separation/dimensions prepared programmatically
with prior user permission. User independently accepted Icon Composer's license.

### Imagegen prompt

```text
Use case: precise-object-edit.
Edit target: the attached FractalLens app icon.
Remove all baked-in lighting effects while preserving the selected Mandelbrot identity and its composition. Turn the icon into a restrained flat two-color graphic: solid dark navy blue (#103B75) Mandelbrot silhouette on a completely uniform pale ice-blue (#D9EDF8) background. Preserve the silhouette's orientation, proportions, bulb structure and recognizable outline; retain useful fractal edge detail but remove cyan glow, bright rim, specular highlights, shading, reflections, glossy streaks, bevels, extrusion, inner shadows, texture and all gradients everywhere.
Apple app-icon production artwork: a full-bleed opaque SQUARE canvas, no rounded-corner mask, no surrounding margin outside the background, no border. The Mandelbrot symbol should remain centered in its existing overall bounding box with comfortable internal margins (approximately 12% at its left/right extremes and 14% top/bottom) so it reads cleanly at Dock sizes. Flat vector-like edges with clean antialiasing. Only the symbol and the pale uniform background. No text or extra symbols.
Output one square 1024x1024 PNG. This is the unmasked artwork source; macOS packaging is done separately.
```
