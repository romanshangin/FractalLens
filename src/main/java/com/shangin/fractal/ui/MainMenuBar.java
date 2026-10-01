package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalDestination;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.SamplingPattern;
import com.shangin.fractal.scene.IterationSettings;
import javafx.application.Platform;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCharacterCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

/** Native macOS menus, with an in-window fallback on other desktop platforms. */
final class MainMenuBar extends MenuBar {
    private final Stage stage;
    private final FractalView fractalView;
    private final MenuItem export;
    private final Menu goTo = new Menu("Go to");
    private final Menu color = new Menu("Color");
    private final Menu render = new Menu("Render");
    private final CheckMenuItem animation = new CheckMenuItem("Animate Palette");
    private final CheckMenuItem histogram = new CheckMenuItem("Histogram Coloring");
    private final CheckMenuItem deepAntialiasing = new CheckMenuItem("Deep Zoom Antialiasing");
    private final ToggleGroup palettes = new ToggleGroup();
    private List<ColorStop> paletteStops;
    private OrbitTrap orbitTrap;
    private Viewport viewport;
    private BigDecimal zoom = BigDecimal.ONE;
    private boolean deepZoom;
    private boolean exporting;
    private boolean rendering = true;
    private FractalPreset currentFractal;

    MainMenuBar(Stage stage, FractalView fractalView, FractalPreset initialFractal,
                PalettePreset initialPalette, Runnable onExport) {
        this(stage, fractalView, FractalScene.create(initialFractal, initialPalette), onExport);
    }

    MainMenuBar(Stage stage, FractalView fractalView, FractalScene initialScene,
                Runnable onExport) {
        this.stage = stage;
        this.fractalView = fractalView;
        FractalPreset initialFractal = initialScene.fractal();
        currentFractal = initialFractal;
        PalettePreset initialPalette = initialScene.coloring().palette();
        this.paletteStops = initialScene.coloring().paletteStops();
        this.orbitTrap = initialScene.coloring().orbitTrap();
        this.viewport = initialScene.viewport();
        setUseSystemMenuBar(true);
        export = command("Export PNG…", shortcut(KeyCode.E, KeyCombination.SHIFT_DOWN), onExport);
        export.setDisable(true);
        Menu file = new Menu("File", null, export, new SeparatorMenuItem(),
                command("Close Window", shortcut(KeyCode.W), stage::close));
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            file.getItems().addAll(new SeparatorMenuItem(), command("Quit", shortcut(KeyCode.Q), Platform::exit));
        }
        Menu view = new Menu("View", null,
                command("Zoom In", new KeyCharacterCombination("+", KeyCombination.SHORTCUT_DOWN),
                        () -> fractalView.zoom(true)),
                command("Zoom Out", shortcut(KeyCode.MINUS), () -> fractalView.zoom(false)),
                command("Reset View", shortcut(KeyCode.DIGIT0), fractalView::resetView),
                new SeparatorMenuItem(),
                command("Go to Coordinates…", shortcut(KeyCode.L), this::showCoordinates));
        MenuItem fullScreen = command("Enter Full Screen",
                new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.CONTROL_DOWN),
                () -> stage.setFullScreen(!stage.isFullScreen()));
        stage.fullScreenProperty().addListener((observable, oldValue, active) ->
                fullScreen.setText(active ? "Exit Full Screen" : "Enter Full Screen"));
        view.getItems().addAll(new SeparatorMenuItem(), fullScreen);
        Menu fractal = choices("Fractal", FractalPreset.values(), initialFractal, new ToggleGroup(), preset -> {
            currentFractal = preset;
            fractalView.setFractal(preset);
            updateDestinations(preset);
            stage.setTitle(preset + " — FractalLens");
        });
        Menu palette = choices("Palette", PalettePreset.values(), initialPalette, palettes, preset -> {
            paletteStops = preset.stops();
            fractalView.setPalette(preset);
        });
        if (!paletteStops.equals(initialPalette.stops())) {
            palettes.selectToggle(null);
        }
        Menu trap = choices("Orbit Trap", OrbitTrap.values(), orbitTrap, new ToggleGroup(), selected -> {
            orbitTrap = selected;
            fractalView.setOrbitTrap(selected);
            updateAvailability();
        });
        animation.setOnAction(event -> fractalView.setColorCycling(animation.isSelected()));
        histogram.setSelected(initialScene.coloring().histogramColoring());
        histogram.setOnAction(event -> {
            fractalView.setHistogramColoring(histogram.isSelected());
            updateAvailability();
        });
        color.getItems().setAll(palette,
                command("Edit Palette…", shortcut(KeyCode.P, KeyCombination.SHIFT_DOWN), this::showPalette),
                new SeparatorMenuItem(), trap, histogram, animation);
        deepAntialiasing.setDisable(true);
        deepAntialiasing.setOnAction(event -> fractalView.setDeepAntialiasing(deepAntialiasing.isSelected()));
        render.getItems().setAll(
                choices("Display Quality", InteractiveRenderMode.values(),
                        initialScene.antialiasing().renderMode(),
                        new ToggleGroup(), fractalView::setInteractiveRenderMode),
                choices("Antialiasing Pattern", SamplingPattern.values(),
                        initialScene.antialiasing().samplingPattern(),
                        new ToggleGroup(), fractalView::setSamplingPattern),
                new SeparatorMenuItem(),
                command("Edit Iteration Settings…", null, this::showIterations),
                command("Edit Julia Parameters…", null, this::showJuliaParameters),
                new SeparatorMenuItem(), deepAntialiasing);
        Menu window = new Menu("Window", null,
                command("Minimize", shortcut(KeyCode.M), () -> stage.setIconified(true)),
                command("Zoom", null, () -> stage.setMaximized(!stage.isMaximized())));
        Menu help = new Menu("Help", null, command("FractalLens Help", null, () -> FractalDialogs.help(stage)));
        updateDestinations(initialFractal);
        getMenus().setAll(file, editMenu(), view, fractal, goTo, color, render, window, help);
        stage.sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene != null) {
                // Also accept the unshifted +/= key and the numeric keypad.
                scene.getAccelerators().put(shortcut(KeyCode.EQUALS, KeyCombination.SHIFT_ANY),
                        () -> fractalView.zoom(true));
                scene.getAccelerators().put(shortcut(KeyCode.ADD), () -> fractalView.zoom(true));
                scene.getAccelerators().put(shortcut(KeyCode.SUBTRACT), () -> fractalView.zoom(false));
            }
        });
        fractalView.setOnZoomChanged(value -> zoom = value);
        fractalView.setOnViewportChanged(value -> viewport = value);
        fractalView.setOnDeepZoomChanged(active -> {
            deepZoom = active;
            if (!active) {
                deepAntialiasing.setSelected(false);
            }
            updateAvailability();
        });
        fractalView.setOnRenderingChanged(active -> {
            rendering = active;
            updateAvailability();
        });
        fractalView.setOnColorCyclingStopped(() -> animation.setSelected(false));
        file.setOnShowing(event -> updateAvailability());
        updateAvailability();
    }

    private void updateDestinations(FractalPreset preset) {
        goTo.getItems().setAll(FractalDestination.forPreset(preset).stream()
                .map(destination -> command(destination.name(), null,
                        () -> fractalView.goTo(destination.viewport())))
                .toList());
    }

    void setExportInProgress(boolean active) {
        exporting = active;
        export.setText(active ? "Exporting PNG…" : "Export PNG…");
        updateAvailability();
    }

    private void updateAvailability() {
        color.setDisable(rendering);
        render.setDisable(rendering);
        export.setDisable(exporting || !fractalView.hasCompletedFrame());
        deepAntialiasing.setDisable(!deepZoom);
        animation.setDisable(histogram.isSelected() || orbitTrap != OrbitTrap.NONE);
        render.getItems().stream()
                .filter(item -> "Edit Julia Parameters…".equals(item.getText()))
                .forEach(item -> item.setDisable(rendering || currentFractal != FractalPreset.JULIA));
    }

    private void showIterations() {
        FractalScene scene = fractalView.sceneSnapshot();
        TextInputDialog base = new TextInputDialog(Integer.toString(scene.iterations().baseIterations()));
        base.setTitle("Iteration settings");
        base.setHeaderText("Base iteration count");
        base.setContentText("Iterations:");
        var baseValue = base.showAndWait();
        if (baseValue.isEmpty()) return;
        TextInputDialog increment = new TextInputDialog(Integer.toString(scene.iterations().iterationsPerZoomLevel()));
        increment.setTitle("Iteration settings");
        increment.setHeaderText("Additional iterations per zoom level");
        increment.setContentText("Iterations per zoom:");
        var incrementValue = increment.showAndWait();
        if (incrementValue.isEmpty()) return;
        try {
            fractalView.setIterations(new IterationSettings(
                    Integer.parseInt(baseValue.get().trim()), Integer.parseInt(incrementValue.get().trim())));
        } catch (RuntimeException exception) {
            FractalDialogs.messageDialog(stage, javafx.scene.control.Alert.AlertType.ERROR,
                    "Invalid iteration settings", "Invalid iteration settings",
                    "Enter a positive base count and a non-negative zoom increment.").showAndWait();
        }
    }

    private void showJuliaParameters() {
        if (currentFractal != FractalPreset.JULIA) return;
        FractalDialogs.juliaParameters(stage, fractalView.sceneSnapshot().juliaParameters())
                .ifPresent(fractalView::setJuliaParameters);
    }

    private void showCoordinates() {
        FractalDialogs.coordinates(stage, viewport, zoom, deepZoom).ifPresent(center ->
                fractalView.setCenter(center.real(), center.imaginary()));
    }

    private void showPalette() {
        FractalDialogs.palette(stage, paletteStops).ifPresent(stops -> {
            if (!stops.equals(paletteStops)) {
                paletteStops = stops;
                palettes.selectToggle(null);
                fractalView.setPaletteStops(stops);
            }
        });
    }

    private static <T> Menu choices(String title, T[] values, T initial, ToggleGroup group,
                                    Consumer<T> onSelected) {
        Menu menu = new Menu(title);
        for (T value : values) {
            RadioMenuItem item = new RadioMenuItem(value.toString());
            item.setToggleGroup(group);
            item.setSelected(value == initial);
            item.setOnAction(event -> {
                // Selecting the active radio item must not clear the current setting.
                item.setSelected(true);
                onSelected.accept(value);
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    private static Menu editMenu() {
        MenuItem undo = textCommand("Undo", KeyCode.Z, TextInputControl::undo);
        MenuItem redo = command("Redo", shortcut(KeyCode.Z, KeyCombination.SHIFT_DOWN),
                () -> withTextInput(TextInputControl::redo));
        MenuItem cut = textCommand("Cut", KeyCode.X, TextInputControl::cut);
        MenuItem copy = textCommand("Copy", KeyCode.C, TextInputControl::copy);
        MenuItem paste = textCommand("Paste", KeyCode.V, TextInputControl::paste);
        MenuItem selectAll = textCommand("Select All", KeyCode.A, TextInputControl::selectAll);
        Menu menu = new Menu("Edit", null, undo, redo, new SeparatorMenuItem(), cut, copy, paste,
                new SeparatorMenuItem(), selectAll);
        menu.setOnShowing(event -> {
            TextInputControl input = focusedTextInput();
            boolean editable = input != null && input.isEditable();
            boolean selected = input != null && input.getSelection().getLength() > 0;
            undo.setDisable(!editable || !input.isUndoable());
            redo.setDisable(!editable || !input.isRedoable());
            cut.setDisable(!editable || !selected);
            copy.setDisable(!selected);
            paste.setDisable(!editable);
            selectAll.setDisable(input == null);
        });
        return menu;
    }

    private static MenuItem textCommand(String title, KeyCode key, Consumer<TextInputControl> action) {
        return command(title, shortcut(key), () -> withTextInput(action));
    }

    private static void withTextInput(Consumer<TextInputControl> action) {
        TextInputControl input = focusedTextInput();
        if (input != null) {
            action.accept(input);
        }
    }

    private static TextInputControl focusedTextInput() {
        for (Window window : Window.getWindows()) {
            if (window.isFocused() && window.getScene() != null
                    && window.getScene().getFocusOwner() instanceof TextInputControl input) {
                return input;
            }
        }
        return null;
    }

    private static MenuItem command(String title, KeyCombination keys, Runnable action) {
        MenuItem item = new MenuItem(title);
        item.setAccelerator(keys);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static KeyCombination shortcut(KeyCode key, KeyCombination.Modifier... modifiers) {
        KeyCombination.Modifier[] all = new KeyCombination.Modifier[modifiers.length + 1];
        all[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(modifiers, 0, all, 1, modifiers.length);
        return new KeyCodeCombination(key, all);
    }
}
