package com.shangin.fractal.ui;

import com.shangin.fractal.controller.RenderStatus;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/** Small, informational overlay; stays outside the rendered/exported image. */
final class RenderModeIndicator extends Label {
    private final Circle dot = new Circle(3.5);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final StackPane statusIcon = new StackPane(dot, spinner);
    private final Tooltip details = new Tooltip();
    private String exportStatus = "";
    private boolean deepZoom;
    private AntialiasSettings antialiasing = new AntialiasSettings();
    private RenderStatus status = new RenderStatus(RenderStatus.State.IDLE, 0);
    private double progress;
    private BigDecimal zoom = BigDecimal.ONE;
    private int iterations;

    RenderModeIndicator() {
        // Reserve the same space for both states so the capsule does not jump.
        statusIcon.setMinSize(12, 12);
        statusIcon.setPrefSize(12, 12);
        statusIcon.setMaxSize(12, 12);
        statusIcon.setMouseTransparent(true);
        spinner.setMinSize(12, 12);
        spinner.setPrefSize(12, 12);
        spinner.setMaxSize(12, 12);
        spinner.setFocusTraversable(false);
        setGraphic(statusIcon);
        setGraphicTextGap(7);
        setPadding(new Insets(6, 10, 6, 10));
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setFocusTraversable(false);
        setStyle("-fx-background-color: rgba(24, 27, 34, 0.78);"
                + "-fx-background-radius: 14; -fx-border-radius: 14;"
                + "-fx-border-color: rgba(255,255,255,0.18);"
                + "-fx-text-fill: #f3f4f7; -fx-font-size: 11px;");
        details.fontProperty().bind(fontProperty());
        details.setShowDelay(Duration.millis(350));
        details.setShowDuration(Duration.INDEFINITE);
        setTooltip(details);
        // Hover is available for the tooltip; interacting with the badge must not pan/zoom.
        addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
        addEventHandler(ZoomEvent.ANY, ZoomEvent::consume);
        update();
    }

    void setDeepZoom(boolean active) {
        deepZoom = active;
        update();
    }

    void setAntialiasSettings(AntialiasSettings antialiasing) {
        this.antialiasing = Objects.requireNonNull(antialiasing);
        update();
    }

    void setRenderStatus(RenderStatus status) {
        this.status = status;
        update();
    }

    void setZoom(BigDecimal zoom) {
        this.zoom = Objects.requireNonNull(zoom);
        update();
    }

    void setIterations(int iterations) {
        this.iterations = iterations;
        update();
    }

    void setProgress(double progress) {
        double bounded = Math.max(0.0, Math.min(1.0, progress));
        if ((int) (this.progress * 100) == (int) (bounded * 100)) return;
        this.progress = bounded;
        update();
    }

    void setExportStatus(String message) {
        exportStatus = Objects.requireNonNull(message);
        update();
    }

    private void update() {
        String mode = deepZoom ? "Deep Zoom" : "Standard";
        String state = switch (status.state()) {
            case IDLE -> "Waiting";
            // Progress covers the whole frame, including any antialiasing pass.
            case RENDERING -> "Rendering " + (int) (progress * 100) + "%";
            case COMPLETE -> "Complete " + formatElapsed(status.elapsedNanos());
            case CANCELLED -> "Interrupted";
            case FAILED -> "Failed";
        };
        setText(mode + " · " + state + (exportStatus.isEmpty() ? "" : " · " + exportStatus));
        String color = deepZoom ? "#e89a91" : "#7dc9e8";
        dot.setFill(Color.web(color));
        spinner.setStyle("-fx-progress-color: " + color + "; -fx-padding: 0;");
        boolean rendering = status.state() == RenderStatus.State.RENDERING;
        spinner.setVisible(rendering);
        dot.setVisible(!rendering);
        String description = deepZoom
                ? "High-precision rendering for deep magnification."
                : "Standard-precision rendering.";
        String antialiasingText = switch (activeMode()) {
            case BASE_ONLY -> "Antialiasing is off: only the base render is calculated.";
            case FAST -> "An antialiasing pass follows the visible base render.";
            case REFINED -> "Only antialiased tiles are displayed.";
        };
        String values = "Zoom: " + RenderValueFormat.zoom(zoom)
                + "\nIterations: " + RenderValueFormat.iterations(iterations);
        details.setText(description + "\n" + antialiasingText + "\n\n" + values);
        setAccessibleText(getText() + ". " + antialiasingText + " " + values.replace('\n', ' '));
    }

    private InteractiveRenderMode activeMode() {
        return deepZoom ? antialiasing.deepZoomRenderMode() : antialiasing.renderMode();
    }

    static String formatElapsed(long nanos) {
        if (nanos < 1_000_000) return "< 1 ms";
        if (nanos < 1_000_000_000) return Math.round(nanos / 1_000_000.0) + " ms";
        return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
    }
}
