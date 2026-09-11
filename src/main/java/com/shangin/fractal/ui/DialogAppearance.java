package com.shangin.fractal.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared JavaFX dialog presentation; preference listeners live only while shown. */
final class DialogAppearance {
    private DialogAppearance() {}

    static void install(Dialog<?> dialog, Window owner, Supplier<Node> initialFocus) {
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setResizable(true);
        var pane = dialog.getDialogPane();
        pane.getStyleClass().add("fractal-dialog");
        pane.getStylesheets().add(Objects.requireNonNull(DialogAppearance.class
                .getResource("fractal-dialog.css")).toExternalForm());
        var appearance = new Session(dialog, owner, initialFocus);
        appearance.update();
        dialog.showingProperty().addListener((observable, wasShowing, showing) -> {
            if (showing) appearance.open(); else appearance.close();
        });
    }

    static Button buttons(Dialog<?> dialog, ButtonType primary, ButtonType... preceding) {
        var pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(preceding);
        pane.getButtonTypes().add(primary);
        // Explicit order also makes the primary action predictable off macOS.
        ((ButtonBar) pane.lookup(".button-bar")).setButtonOrder(ButtonBar.BUTTON_ORDER_MAC_OS);
        for (ButtonType type : pane.getButtonTypes()) {
            Button button = (Button) pane.lookupButton(type);
            button.setDefaultButton(type == primary);
            button.setCancelButton(type.getButtonData().isCancelButton() || preceding.length == 0);
        }
        return (Button) pane.lookupButton(primary);
    }

    private static final class Session {
        private final Dialog<?> dialog;
        private final Window owner;
        private final Supplier<Node> initialFocus;
        private Scene scene;
        private Node previousFocus;
        private final InvalidationListener changed = ignored -> update();

        private Session(Dialog<?> dialog, Window owner, Supplier<Node> initialFocus) {
            this.dialog = dialog; this.owner = owner; this.initialFocus = initialFocus;
        }

        private void open() {
            scene = dialog.getDialogPane().getScene();
            previousFocus = owner == null || owner.getScene() == null ? null : owner.getScene().getFocusOwner();
            scene.getPreferences().colorSchemeProperty().addListener(changed);
            Platform.getPreferences().accentColorProperty().addListener(changed);
            update();
            Platform.runLater(() -> {
                if (!dialog.isShowing()) return;
                Node node = initialFocus.get();
                if (node != null) {
                    node.requestFocus();
                    if (node instanceof TextInputControl text) text.selectAll();
                }
            });
        }

        private void update() {
            Scene current = scene != null ? scene : dialog.getDialogPane().getScene();
            boolean dark = (current == null ? Platform.getPreferences().getColorScheme()
                    : current.getPreferences().getColorScheme()) == ColorScheme.DARK;
            Color accent = Platform.getPreferences().getAccentColor();
            // Select black/white by luminance so custom system accents stay legible.
            double luminance = .2126 * linear(accent.getRed()) + .7152 * linear(accent.getGreen())
                    + .0722 * linear(accent.getBlue());
            dialog.getDialogPane().setStyle(String.format(Locale.ROOT,
                    "-dialog-background: %s; -dialog-input: %s; -dialog-text: %s; "
                    + "-dialog-muted: %s; -dialog-border: %s; -dialog-error: %s; "
                    + "-fx-accent: rgb(%d,%d,%d); -dialog-accent-text: %s;",
                    dark ? "#292929" : "#f5f5f5", dark ? "#383838" : "#ffffff",
                    dark ? "#f2f2f2" : "#202020", dark ? "#bbbbbb" : "#606060",
                    dark ? "#626262" : "#bdbdbd", dark ? "#ff9b91" : "#b3261e",
                    Math.round(accent.getRed() * 255), Math.round(accent.getGreen() * 255),
                    Math.round(accent.getBlue() * 255), luminance > .179 ? "#000000" : "#ffffff"));
        }

        private void close() {
            if (scene == null) return;
            scene.getPreferences().colorSchemeProperty().removeListener(changed);
            Platform.getPreferences().accentColorProperty().removeListener(changed);
            scene = null;
            Node restore = previousFocus;
            previousFocus = null;
            Platform.runLater(() -> {
                if (!dialog.isShowing() && restore != null && owner != null && owner.isShowing()
                        && owner.isFocused() && restore.getScene() == owner.getScene()
                        && restore.isVisible() && !restore.isDisabled()) restore.requestFocus();
            });
        }
    }

    private static double linear(double value) {
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
