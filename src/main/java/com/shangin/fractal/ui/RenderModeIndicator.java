package com.shangin.fractal.ui;

import com.shangin.fractal.controller.RenderStatus;
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
    private boolean deepZoom;
    private boolean deepAntialiasing;
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
        if (!active) deepAntialiasing = false;
        update();
    }

    void setDeepAntialiasing(boolean enabled) {
        deepAntialiasing = deepZoom && enabled;
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

    private void update() {
        String mode = deepZoom ? "Deep Zoom" : "Standard";
        String state = switch (status.state()) {
            case IDLE -> "Waiting";
            case RENDERING -> progress >= 1.0 ? "Refining…"
                    : "Rendering " + (int) (progress * 100) + "%";
            case COMPLETE -> "Complete " + formatElapsed(status.elapsedNanos());
            case CANCELLED -> "Interrupted";
            case FAILED -> "Failed";
        };
        setText(mode + " · " + state);
        String color = deepZoom ? "#e89a91" : "#7dc9e8";
        dot.setFill(Color.web(color));
        spinner.setStyle("-fx-progress-color: " + color + "; -fx-padding: 0;");
        boolean rendering = status.state() == RenderStatus.State.RENDERING;
        spinner.setVisible(rendering);
        dot.setVisible(!rendering);
        String description = deepZoom
                ? "High-precision rendering for deep magnification."
                : "Standard-precision rendering.";
        String antialiasing = deepZoom
                ? (deepAntialiasing
                    ? "Deep Zoom Antialiasing is on: an additional smoothing pass follows the base render."
                    : "Deep Zoom Antialiasing is off: only the fast base render is calculated.")
                : "Antialiasing is included in the render.";
        String values = "Zoom: " + RenderValueFormat.zoom(zoom)
                + "\nIterations: " + RenderValueFormat.iterations(iterations);
        details.setText(description + "\n" + antialiasing + "\n\n" + values);
        setAccessibleText(getText() + ". " + antialiasing + " " + values.replace('\n', ' '));
    }

    static String formatElapsed(long nanos) {
        if (nanos < 1_000_000) return "< 1 ms";
        if (nanos < 1_000_000_000) return Math.round(nanos / 1_000_000.0) + " ms";
        return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
    }
}
