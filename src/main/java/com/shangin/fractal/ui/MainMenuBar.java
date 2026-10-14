package com.shangin.fractal.ui;

import com.shangin.fractal.app.BookmarkStore;
import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalDestination;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.SamplingPattern;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
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
    private final SceneActions sceneActions;
    private final javafx.event.EventHandler<javafx.stage.WindowEvent> hiddenHandler = event -> close();
    private final ToggleGroup fractals = new ToggleGroup();
    private final ToggleGroup quality = new ToggleGroup();
    private final ToggleGroup sampling = new ToggleGroup();
    private MenuItem undo;
    private MenuItem redo;
    private Scene observedScene;
    private TextInputControl observedTextInput;
    private final InvalidationListener textHistoryListener = observable -> updateHistoryAvailability();
    private final ChangeListener<Node> focusListener = (observable, oldValue, newValue) -> updateHistoryAvailability();
    private final ChangeListener<Scene> sceneListener = (observable, oldValue, newValue) -> observeActiveScene();
    private final InvalidationListener windowFocusListener = observable -> observeActiveScene();
    private final java.util.Set<Window> observedWindows = new java.util.HashSet<>();
    private final ListChangeListener<Window> windowsListener = change -> {
        while (change.next()) {
            for (Window window : change.getRemoved()) unobserveWindow(window);
            for (Window window : change.getAddedSubList()) observeWindow(window);
        }
        observeActiveScene();
    };
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
    private final ToggleGroup orbitTraps = new ToggleGroup();
    private List<ColorStop> paletteStops;
    private OrbitTrap orbitTrap;
    private Viewport viewport;
    private BigDecimal zoom = BigDecimal.ONE;
    private boolean deepZoom;
    private boolean exporting;
    private boolean rendering = true;
    private FractalPreset currentFractal;
    private MacIterationSheet iterationSheet;
    private MacJuliaSheet juliaSheet;

    MainMenuBar(Stage stage, FractalView fractalView, FractalPreset initialFractal,
                PalettePreset initialPalette, Runnable onExport) {
        this(stage, fractalView, FractalScene.create(initialFractal, initialPalette), onExport);
    }

    MainMenuBar(Stage stage, FractalView fractalView, FractalScene initialScene,
                Runnable onExport) {
        this(stage, fractalView, initialScene, onExport, BookmarkStore.systemDefault());
    }

    MainMenuBar(Stage stage, FractalView fractalView, FractalScene initialScene,
                Runnable onExport, BookmarkStore bookmarks) {
        this.stage = stage;
        this.fractalView = fractalView;
        sceneActions = new SceneActions(stage, fractalView, bookmarks);
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, hiddenHandler);
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
        Menu fractal = choices("Fractal", FractalPreset.values(), initialFractal, fractals, preset -> {
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
        Menu trap = choices("Orbit Trap", OrbitTrap.values(), orbitTrap, orbitTraps, selected -> {
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
                new SeparatorMenuItem(), trap, histogram, animation,
                new SeparatorMenuItem(), command("Reset Colors to Defaults", null, this::resetColors));
        deepAntialiasing.setDisable(true);
        deepAntialiasing.setOnAction(event -> fractalView.setDeepAntialiasing(deepAntialiasing.isSelected()));
        render.getItems().setAll(
                choices("Display Quality", InteractiveRenderMode.values(),
                        initialScene.antialiasing().renderMode(),
                        quality, fractalView::setInteractiveRenderMode),
                choices("Antialiasing Pattern", SamplingPattern.values(),
                        initialScene.antialiasing().samplingPattern(),
                        sampling, fractalView::setSamplingPattern),
                new SeparatorMenuItem(),
                command("Edit Iteration Settings…", null, this::showIterations),
                command("Edit Julia Parameters…", null, this::showJuliaParameters),
                new SeparatorMenuItem(), deepAntialiasing);
        Menu window = new Menu("Window", null,
                command("Minimize", shortcut(KeyCode.M), () -> stage.setIconified(true)),
                command("Zoom", null, () -> stage.setMaximized(!stage.isMaximized())));
        Menu help = new Menu("Help", null, command("FractalLens Help", null, () -> FractalDialogs.help(stage)));
        updateDestinations(initialFractal);
        view.getItems().addAll(new SeparatorMenuItem(),
                command("Copy Scene Preset", shortcut(KeyCode.C, KeyCombination.SHIFT_DOWN), sceneActions::copyPreset),
                command("Apply Scene Preset from Clipboard", shortcut(KeyCode.V, KeyCombination.SHIFT_DOWN), sceneActions::pastePreset));
        getMenus().setAll(file, editMenu(), view, fractal, goTo, sceneActions.bookmarksMenu(), color, render, window, help);
        Window.getWindows().addListener(windowsListener);
        // Observe the owner before it is shown, then any separate dialog windows.
        observeWindow(stage);
        Window.getWindows().forEach(this::observeWindow);
        observeActiveScene();
        fractalView.setOnSceneChanged(this::syncScene);
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
        // Keep the menu containers enabled. AppKit validation can disable their
        // children without changing JavaFX properties, leaving stale native state
        // when only the parent is re-enabled after rendering.
        setCommandsDisabled(color, rendering);
        setCommandsDisabled(render, rendering);
        export.setDisable(exporting || !fractalView.hasCompletedFrame());
        deepAntialiasing.setDisable(rendering || !deepZoom);
        animation.setDisable(rendering || histogram.isSelected() || orbitTrap != OrbitTrap.NONE);
        render.getItems().stream()
                .filter(item -> "Edit Julia Parameters…".equals(item.getText()))
                .forEach(item -> item.setDisable(rendering || currentFractal != FractalPreset.JULIA));
    }

    private static void setCommandsDisabled(Menu menu, boolean disabled) {
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu submenu) {
                setCommandsDisabled(submenu, disabled);
            } else if (!(item instanceof SeparatorMenuItem)) {
                item.setDisable(disabled);
            }
        }
    }

    private void showIterations() {
        if (parameterSheetOpen()) return;
        if (MacIterationSheet.isAvailable()) {
            try {
                iterationSheet = MacIterationSheet.show(stage, fractalView.sceneSnapshot().iterations(),
                        fractalView::setIterations);
                return;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                System.getLogger(MainMenuBar.class.getName()).log(System.Logger.Level.WARNING,
                        "Native iteration sheet unavailable; using JavaFX dialog", error);
            }
        }
        FractalDialogs.iterationSettings(stage, fractalView.sceneSnapshot().iterations())
                .ifPresent(fractalView::setIterations);
    }

    private void showJuliaParameters() {
        if (currentFractal != FractalPreset.JULIA) return;
        if (parameterSheetOpen()) return;
        if (MacJuliaSheet.isAvailable()) {
            try {
                juliaSheet = MacJuliaSheet.show(stage, fractalView.sceneSnapshot().juliaParameters(),
                        fractalView::setJuliaParameters);
                return;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                System.getLogger(MainMenuBar.class.getName()).log(System.Logger.Level.WARNING,
                        "Native Julia sheet unavailable; using JavaFX dialog", error);
            }
        }
        FractalDialogs.juliaParameters(stage, fractalView.sceneSnapshot().juliaParameters())
                .ifPresent(fractalView::setJuliaParameters);
    }

    private boolean parameterSheetOpen() {
        return iterationSheet != null && iterationSheet.isOpen()
                || juliaSheet != null && juliaSheet.isOpen();
    }

    private void showCoordinates() {
        FractalDialogs.coordinates(stage, viewport, zoom, deepZoom).ifPresent(center ->
                fractalView.setCenter(center.real(), center.imaginary()));
    }

    private void resetColors() {
        fractalView.resetColorsToDefaults();
        var defaults = fractalView.sceneSnapshot().coloring();
        paletteStops = defaults.paletteStops();
        orbitTrap = defaults.orbitTrap();
        palettes.selectToggle(palettes.getToggles().get(defaults.palette().ordinal()));
        orbitTraps.selectToggle(orbitTraps.getToggles().get(orbitTrap.ordinal()));
        histogram.setSelected(defaults.histogramColoring());
        animation.setSelected(false);
        updateAvailability();
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

    private Menu editMenu() {
        undo = command("Undo", shortcut(KeyCode.Z), () -> {
            TextInputControl input = focusedTextInput();
            if (input != null) input.undo();
            else fractalView.undoScene();
        });
        redo = command("Redo", shortcut(KeyCode.Z, KeyCombination.SHIFT_DOWN), () -> {
            TextInputControl input = focusedTextInput();
            if (input != null) input.redo();
            else fractalView.redoScene();
        });
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
            updateHistoryAvailability();
            cut.setDisable(!editable || !selected);
            copy.setDisable(!selected);
            paste.setDisable(!editable);
            selectAll.setDisable(input == null);
        });
        undo.setDisable(!fractalView.canUndo());
        redo.setDisable(!fractalView.canRedo());
        return menu;
    }

    void close() {
        stage.removeEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, hiddenHandler);
        Window.getWindows().removeListener(windowsListener);
        java.util.List.copyOf(observedWindows).forEach(this::unobserveWindow);
        if (observedScene != null) observedScene.focusOwnerProperty().removeListener(focusListener);
        observeTextInput(null);
        sceneActions.close();
        if (iterationSheet != null) iterationSheet.close();
        if (juliaSheet != null) juliaSheet.close();
    }

    private void syncScene(FractalScene scene) {
        currentFractal = scene.fractal();
        viewport = scene.viewport();
        paletteStops = scene.coloring().paletteStops();
        orbitTrap = scene.coloring().orbitTrap();
        fractals.selectToggle(fractals.getToggles().get(scene.fractal().ordinal()));
        palettes.selectToggle(paletteStops.equals(scene.coloring().palette().stops())
                ? palettes.getToggles().get(scene.coloring().palette().ordinal()) : null);
        orbitTraps.selectToggle(orbitTraps.getToggles().get(orbitTrap.ordinal()));
        quality.selectToggle(quality.getToggles().get(scene.antialiasing().renderMode().ordinal()));
        sampling.selectToggle(sampling.getToggles().get(scene.antialiasing().samplingPattern().ordinal()));
        histogram.setSelected(scene.coloring().histogramColoring());
        updateHistoryAvailability();
        stage.setTitle(scene.fractal() + " — FractalLens");
        updateDestinations(scene.fractal());
        updateAvailability();
    }

    private void observeWindow(Window window) {
        if (!observedWindows.add(window)) return;
        window.sceneProperty().addListener(sceneListener);
        window.focusedProperty().addListener(windowFocusListener);
    }

    private void unobserveWindow(Window window) {
        observedWindows.remove(window);
        window.sceneProperty().removeListener(sceneListener);
        window.focusedProperty().removeListener(windowFocusListener);
    }

    private void observeActiveScene() {
        Scene scene = Window.getWindows().stream().filter(Window::isFocused)
                .map(Window::getScene).filter(java.util.Objects::nonNull)
                .findFirst().orElse(stage.getScene());
        observeScene(scene);
    }

    private void observeScene(Scene scene) {
        if (observedScene != null) observedScene.focusOwnerProperty().removeListener(focusListener);
        observedScene = scene;
        if (scene != null) scene.focusOwnerProperty().addListener(focusListener);
        updateHistoryAvailability();
    }

    private void observeTextInput(TextInputControl input) {
        if (observedTextInput == input) return;
        if (observedTextInput != null) {
            observedTextInput.undoableProperty().removeListener(textHistoryListener);
            observedTextInput.redoableProperty().removeListener(textHistoryListener);
            observedTextInput.editableProperty().removeListener(textHistoryListener);
        }
        observedTextInput = input;
        if (input != null) {
            input.undoableProperty().addListener(textHistoryListener);
            input.redoableProperty().addListener(textHistoryListener);
            input.editableProperty().addListener(textHistoryListener);
        }
    }

    private void updateHistoryAvailability() {
        TextInputControl input = focusedTextInput();
        observeTextInput(input);
        undo.setDisable(input != null ? !input.isEditable() || !input.isUndoable() : !fractalView.canUndo());
        redo.setDisable(input != null ? !input.isEditable() || !input.isRedoable() : !fractalView.canRedo());
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
