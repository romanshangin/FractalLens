package com.shangin.fractal.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Skin;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Canvas commands, presentation and dismissal live here as the menu grows. */
final class FractalContextMenu extends ContextMenu implements AutoCloseable {
    static final String COPY_COORDINATES_AND_ZOOM = "Copy Coordinates and Zoom";

    /** One menu entry; its action runs on the JavaFX thread after the menu has closed. */
    record Command(String title, Runnable action) {
        Command {
            Objects.requireNonNull(title);
            Objects.requireNonNull(action);
        }
    }

    private final Node owner;
    private final Function<Point2D, List<Command>> commands;
    private final BooleanSupplier nativeMenus;
    private MacContextMenu nativeMenu;
    private boolean closed;
    private Window observedWindow;
    private Scene observedScene;
    private final InvalidationListener dismiss = ignored -> hide();
    private final InvalidationListener appearanceChanged = ignored -> updateAppearance();
    private final InvalidationListener focusChanged = ignored -> {
        if (observedWindow != null && !observedWindow.isFocused()) hide();
    };
    private final EventHandler<InputEvent> interaction = event -> {
        if (event instanceof ScrollEvent || event instanceof ZoomEvent
                || event instanceof SwipeEvent || event instanceof RotateEvent
                || event instanceof TouchEvent || event.getEventType() == KeyEvent.KEY_PRESSED
                || event.getEventType() == MouseEvent.MOUSE_PRESSED
                || event.getEventType() == MouseEvent.MOUSE_DRAGGED) {
            hide();
        }
    };
    private final EventHandler<ContextMenuEvent> request = this::showForRequest;

    private void showForRequest(ContextMenuEvent event) {
        if (closed) return;
        hide();
        var local = owner.screenToLocal(event.getScreenX(), event.getScreenY());
        // Commands bind the clicked point and the state it was taken from at request time.
        List<Command> requested = List.copyOf(commands.apply(local));
        if (requested.isEmpty()) return;
        setCommands(requested);
        if (nativeMenus.getAsBoolean() && owner.getScene() != null
                && owner.getScene().getWindow() != null) {
            var point = local == null ? null : owner.localToScene(local);
            if (point != null) {
                try {
                    var titles = requested.stream().map(Command::title).toList();
                    nativeMenu = MacContextMenu.show(owner.getScene().getWindow(), point.getX(), point.getY(), titles, selected -> {
                        nativeMenu = null;
                        stopObservingWindow();
                        if (selected >= 0 && selected < requested.size() && !closed) {
                            requested.get(selected).action().run();
                        }
                    });
                    observeWindow();
                    event.consume();
                    return;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                            "Cannot open AppKit menu; using JavaFX menu", error);
                }
            }
        }
        show(owner, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    FractalContextMenu(Node owner, Runnable copyCoordinatesAndZoom) {
        this(owner, ignored -> List.of(new Command(COPY_COORDINATES_AND_ZOOM, copyCoordinatesAndZoom)));
        setCommands(commands.apply(null));
    }

    /**
     * Creates a menu whose entries are built for each request from the owner-local
     * point that was clicked ({@code null} when it cannot be resolved). An empty list
     * shows no menu.
     */
    FractalContextMenu(Node owner, Function<Point2D, List<Command>> commands) {
        this(owner, commands, MacContextMenu::isAvailable);
    }

    /** {@code nativeMenus} decides per request whether AppKit is tried before the JavaFX menu. */
    FractalContextMenu(Node owner, Function<Point2D, List<Command>> commands, BooleanSupplier nativeMenus) {
        this.owner = Objects.requireNonNull(owner);
        this.commands = Objects.requireNonNull(commands);
        this.nativeMenus = Objects.requireNonNull(nativeMenus);
        getStyleClass().add("fractal-context-menu");
        updateAppearance();
        setAutoHide(true);
        setHideOnEscape(true);
        // The click that dismisses the popup must still reach the canvas.
        setConsumeAutoHidingEvents(false);
        setOnShowing(event -> {
            updateAppearance();
            observeWindow();
        });
        setOnHidden(event -> stopObservingWindow());
        owner.addEventFilter(InputEvent.ANY, interaction);
        owner.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, request);
        owner.sceneProperty().addListener(dismiss);
    }

    @Override protected Skin<?> createDefaultSkin() {
        Skin<?> skin = super.createDefaultSkin();
        // Popup CSS inherits the owner's stylesheets, not its own Scene's list.
        ((Parent) skin.getNode()).getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("fractal-context-menu.css")).toExternalForm());
        return skin;
    }

    private void setCommands(List<Command> requested) {
        getItems().setAll(requested.stream().map(command -> {
            MenuItem item = new MenuItem(command.title());
            item.setOnAction(event -> {
                hide();
                command.action().run();
            });
            return item;
        }).toList());
    }

    private void updateAppearance() {
        var preferences = Platform.getPreferences();
        applyAppearance(preferences.getColorScheme(), preferences.getAccentColor());
    }

    /** Styles the JavaFX menu for a color scheme; the platform scheme is applied again on showing. */
    void applyAppearance(ColorScheme scheme, Color accent) {
        boolean dark = scheme == ColorScheme.DARK;
        setStyle(String.format(Locale.ROOT,
                "-menu-background: %s; -menu-foreground: %s; -menu-border: %s; "
                        + "-menu-accent: rgb(%d,%d,%d);",
                dark ? "rgba(38,38,40,0.90)" : "rgba(250,250,252,0.92)",
                dark ? "#f5f5f5" : "#202020",
                dark ? "rgba(255,255,255,0.22)" : "rgba(0,0,0,0.12)",
                Math.round(accent.getRed() * 255), Math.round(accent.getGreen() * 255),
                Math.round(accent.getBlue() * 255)));
    }

    private void observeWindow() {
        stopObservingWindow();
        observedScene = owner.getScene();
        observedWindow = observedScene == null ? null : observedScene.getWindow();
        if (observedScene != null) observedScene.windowProperty().addListener(dismiss);
        if (observedWindow != null) {
            observedWindow.xProperty().addListener(dismiss);
            observedWindow.yProperty().addListener(dismiss);
            observedWindow.widthProperty().addListener(dismiss);
            observedWindow.heightProperty().addListener(dismiss);
            observedWindow.showingProperty().addListener(dismiss);
            observedWindow.focusedProperty().addListener(focusChanged);
        }
        Platform.getPreferences().colorSchemeProperty().addListener(appearanceChanged);
        Platform.getPreferences().accentColorProperty().addListener(appearanceChanged);
    }

    private void stopObservingWindow() {
        if (observedScene != null) {
            observedScene.windowProperty().removeListener(dismiss);
            observedScene = null;
        }
        if (observedWindow != null) {
            observedWindow.xProperty().removeListener(dismiss);
            observedWindow.yProperty().removeListener(dismiss);
            observedWindow.widthProperty().removeListener(dismiss);
            observedWindow.heightProperty().removeListener(dismiss);
            observedWindow.showingProperty().removeListener(dismiss);
            observedWindow.focusedProperty().removeListener(focusChanged);
            observedWindow = null;
        }
        Platform.getPreferences().colorSchemeProperty().removeListener(appearanceChanged);
        Platform.getPreferences().accentColorProperty().removeListener(appearanceChanged);
    }

    boolean hasNativeMenu() { return nativeMenu != null; }

    @Override public void hide() {
        if (nativeMenu != null) {
            nativeMenu.close();
            nativeMenu = null;
            stopObservingWindow();
        }
        super.hide();
    }

    @Override public void close() {
        closed = true;
        hide();
        stopObservingWindow();
        owner.removeEventFilter(InputEvent.ANY, interaction);
        owner.removeEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, request);
        owner.sceneProperty().removeListener(dismiss);
    }
}
