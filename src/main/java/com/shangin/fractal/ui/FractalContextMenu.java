package com.shangin.fractal.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Skin;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.Locale;
import java.util.Objects;

/** Canvas commands, presentation and dismissal live here as the menu grows. */
final class FractalContextMenu extends ContextMenu implements AutoCloseable {
    private final Node owner;
    private Window observedWindow;
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
        hide();
        show(owner, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    FractalContextMenu(Node owner, Runnable copyCoordinatesAndZoom) {
        this.owner = Objects.requireNonNull(owner);
        MenuItem copy = new MenuItem("Copy Coordinates and Zoom");
        copy.setOnAction(event -> {
            hide();
            copyCoordinatesAndZoom.run();
        });
        getItems().setAll(copy);
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

    private void updateAppearance() {
        var preferences = Platform.getPreferences();
        boolean dark = preferences.getColorScheme() == ColorScheme.DARK;
        Color accent = preferences.getAccentColor();
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
        observedWindow = owner.getScene() == null ? null : owner.getScene().getWindow();
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

    @Override public void close() {
        hide();
        stopObservingWindow();
        owner.removeEventFilter(InputEvent.ANY, interaction);
        owner.removeEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, request);
        owner.sceneProperty().removeListener(dismiss);
    }
}
