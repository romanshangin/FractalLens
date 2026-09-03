# FractalUI — матовая иконка

Выбранный пользователем Мандельброт: однотонный синий силуэт `#103B75` на светлом фоне `#D9EDF8`. Из рисунка убраны блики, свечение, текстура, объём и градиенты. Силуэт и фон разделены; нативные слои квадратные, без заранее скруглённых углов.

- `matte-square.png` — сохранённый результат правки через встроенный imagegen.
- `mandelbrot.png` — прозрачный слой силуэта, 1024×1024.
- `flat-square.png` — плоская композиция, 1024×1024, без маски.
- `FractalUI.icon` — проект, созданный и проверенный в Apple Icon Composer. Эффекты слоя, блики группы, полупрозрачность и тени отключены. Скругление и системное оформление фона оставлены macOS.
- `macos-legacy-mask.png` — альфа-маска из экспорта Xcode, используется только для совместимости с Java Dock API и ICNS.

PNG и ICNS приложения находятся в `src/main/resources/com/shangin/fractal/app/icons/`. Они используют плоский рисунок без нарисованных бликов. Maven и общая конфигурация IntelliJ передают ICNS в `-Xdock:icon` до инициализации JavaFX.

## Пересборка

Из корня проекта, с установленным Pillow:

```sh
python3 scripts/build_app_icon.py
```

Скомпилировать нативный исходник инструментами Xcode:

```sh
mkdir -p target/native-icon
xcrun actool design/app-icon/FractalUI.icon \
  --compile target/native-icon --platform macosx \
  --minimum-deployment-target 13.0 --app-icon FractalUI \
  --output-partial-info-plist target/native-icon/icon-info.plist \
  --output-format human-readable-text
```

Нативный экспорт содержит `Assets.car`, `FractalUI.icns` и ключи Info.plist. Для `.app` с нативной многослойной иконкой нужны оба ресурса и `CFBundleIconName=FractalUI`; одной копии `.icon` в JAR недостаточно. Текущий запуск JavaFX из Maven/IDE использует совместимые PNG/ICNS. Обычная упаковка `jpackage --icon src/main/resources/com/shangin/fractal/app/icons/FractalUI.icns` также поддерживается.

## Проверка

Проект открыт в Icon Composer и успешно скомпилирован `actool`. Рисунок проверен в нативном экспорте. Реальная иконка Dock прочитана до и после установки через Java Taskbar: обе соответствуют новому Мандельброту. Слой силуэта сохраняет прозрачные поля, фон композиции непрозрачный.

## Источники и происхождение

- [Apple: App icons](https://developer.apple.com/design/human-interface-guidelines/app-icons)
- [Apple: Creating your app icon using Icon Composer](https://developer.apple.com/documentation/xcode/creating-your-app-icon-using-icon-composer)
- [Java: параметры macOS](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#extra-options-for-macos)

Правка выполнена встроенным imagegen; альфа-канал, разделение слоёв и размеры подготовлены программно с ранее полученным разрешением пользователя. Лицензию Icon Composer пользователь принял самостоятельно.

### Промпт imagegen

```text
Use case: precise-object-edit.
Edit target: the attached FractalUI app icon.
Remove all baked-in lighting effects while preserving the selected Mandelbrot identity and its composition. Turn the icon into a restrained flat two-color graphic: solid dark navy blue (#103B75) Mandelbrot silhouette on a completely uniform pale ice-blue (#D9EDF8) background. Preserve the silhouette's orientation, proportions, bulb structure and recognizable outline; retain useful fractal edge detail but remove cyan glow, bright rim, specular highlights, shading, reflections, glossy streaks, bevels, extrusion, inner shadows, texture and all gradients everywhere.
Apple app-icon production artwork: a full-bleed opaque SQUARE canvas, no rounded-corner mask, no surrounding margin outside the background, no border. The Mandelbrot symbol should remain centered in its existing overall bounding box with comfortable internal margins (approximately 12% at its left/right extremes and 14% top/bottom) so it reads cleanly at Dock sizes. Flat vector-like edges with clean antialiasing. Only the symbol and the pale uniform background. No text or extra symbols.
Output one square 1024x1024 PNG. This is the unmasked artwork source; macOS packaging is done separately.
```
