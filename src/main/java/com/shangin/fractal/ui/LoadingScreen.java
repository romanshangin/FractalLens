package com.shangin.fractal.ui;

import javafx.animation.AnimationTimer;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.StackPane;

/** Owns both the theme-aware fallback and the temporary native material. */
final class LoadingScreen extends StackPane implements AutoCloseable {
    private MacLoadingMaterial material;
    private final Runnable materialShown;
    private final AnimationTimer attach = new AnimationTimer() {
        @Override public void handle(long now) {
            if (getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isShowing()) return;
            stop();
            if (!System.getProperty("os.name", "").startsWith("Mac")) return;
            try {
                material = MacLoadingMaterial.show(getScene().getWindow());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                System.err.println("Native loading material unavailable; using system theme: " + error);
                return;
            }
            materialShown.run();
        }
    };

    LoadingScreen() {
        this(() -> {});
    }

    /** {@code materialShown} runs on the FX thread after the native material covers the window. */
    LoadingScreen(Runnable materialShown) {
        this.materialShown = java.util.Objects.requireNonNull(materialShown);
        var resource = java.util.Objects.requireNonNull(getClass().getResource(
                "/com/shangin/fractal/app/icons/fractallens.png"));
        ImageView icon = new ImageView(new Image(resource.toExternalForm(), 256, 256, true, true));
        icon.setFitWidth(96);
        icon.setFitHeight(96);
        icon.setPreserveRatio(true);
        getChildren().add(icon);
        var scheme = Platform.getPreferences().colorSchemeProperty();
        styleProperty().bind(Bindings.createStringBinding(
                () -> "-fx-background-color: " + (scheme.get() == ColorScheme.DARK ? "#242424;" : "#f2f2f2;"), scheme));
        setAccessibleText("Rendering fractal image");
        addEventFilter(InputEvent.ANY, event -> event.consume());
        attach.start();
    }

    boolean hasNativeMaterial() { return material != null; }

    @Override public void close() {
        attach.stop();
        styleProperty().unbind();
        if (material != null) { material.close(); material = null; }
    }
}
