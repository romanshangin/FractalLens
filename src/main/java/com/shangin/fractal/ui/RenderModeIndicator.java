package com.shangin.fractal.ui;

import com.shangin.fractal.controller.RenderStatus;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.stage.Window;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * Small, informational overlay; stays outside the rendered/exported image. On macOS
 * the badge is presented by an AppKit view with the system material while this label
 * keeps the state and remains the fallback presentation everywhere else.
 */
final class RenderModeIndicator extends Label implements AutoCloseable {
    private static final double ICON_SIZE = 12;
    private static final double ICON_GAP = 7;
    private final Circle dot = new Circle(3.5);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final StackPane statusIcon = new StackPane(dot, spinner);
    private final Tooltip details = new Tooltip();
    private String exportStatus = "";
    private boolean deepZoom;
    private RenderStatus status = new RenderStatus(RenderStatus.State.IDLE, 0);
    private double progress;
    private BigDecimal zoom = BigDecimal.ONE;
    private int iterations;
    private final Text measurer = new Text();
    private final ObservableValue<Boolean> windowShowing = sceneProperty()
            .flatMap(Scene::windowProperty).flatMap(Window::showingProperty).orElse(false);
    private final ChangeListener<Boolean> nativeLifecycle = (observable, was, showing) -> {
        if (showing) showNative(); else closeNative();
    };
    private final ChangeListener<Object> nativePlacement = (observable, oldValue, newValue) -> placeNative();
    private MacStatusBadge nativeBadge;
    private boolean closed;

    RenderModeIndicator() {
        // Reserve the same space for both states so the capsule does not jump.
        statusIcon.setMinSize(ICON_SIZE, ICON_SIZE);
        statusIcon.setPrefSize(ICON_SIZE, ICON_SIZE);
        statusIcon.setMaxSize(ICON_SIZE, ICON_SIZE);
        statusIcon.setMouseTransparent(true);
        spinner.setMinSize(ICON_SIZE, ICON_SIZE);
        spinner.setPrefSize(ICON_SIZE, ICON_SIZE);
        spinner.setMaxSize(ICON_SIZE, ICON_SIZE);
        spinner.setFocusTraversable(false);
        setGraphic(statusIcon);
        setGraphicTextGap(ICON_GAP);
        setPadding(new Insets(6, 10, 6, 10));
        setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setFocusTraversable(false);
        // Small system text (11 pt), as macOS uses for status overlays.
        setStyle("-fx-background-color: rgba(24, 27, 34, 0.78);"
                + "-fx-background-radius: 14; -fx-border-radius: 14;"
                + "-fx-border-color: rgba(255,255,255,0.18);"
                + "-fx-text-fill: #f3f4f7; -fx-font-family: System; -fx-font-size: 11px;");
        details.setStyle("-fx-font-family: System; -fx-font-size: 12px; -fx-padding: 6 9 6 9;"
                + "-fx-background-radius: 6; -fx-line-spacing: 1px;");
        details.setShowDelay(Duration.millis(350));
        details.setShowDuration(Duration.INDEFINITE);
        setTooltip(details);
        // Hover is available for the tooltip; interacting with the badge must not pan/zoom.
        addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
        addEventHandler(ZoomEvent.ANY, ZoomEvent::consume);
        fontProperty().addListener((observable, oldFont, newFont) -> update());
        windowShowing.addListener(nativeLifecycle);
        localToSceneTransformProperty().addListener(nativePlacement);
        layoutBoundsProperty().addListener(nativePlacement);
        update();
    }

    /** True while AppKit presents the badge instead of this label. */
    boolean hasNativePresentation() { return nativeBadge != null; }

    /** Native view state for tests; see {@link MacStatusBadge#state()}. */
    double[] nativeState() {
        if (nativeBadge == null) throw new IllegalStateException("No native presentation");
        return nativeBadge.state();
    }

    /** Restores the badge above native overlays attached after it, such as the loading material. */
    void raiseNative() {
        if (nativeBadge != null) nativeBadge.raise();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        windowShowing.removeListener(nativeLifecycle);
        localToSceneTransformProperty().removeListener(nativePlacement);
        layoutBoundsProperty().removeListener(nativePlacement);
        closeNative();
    }

    private void showNative() {
        if (closed || nativeBadge != null || !MacContextMenu.isAvailable()) return;
        try {
            nativeBadge = MacStatusBadge.show(getScene().getWindow());
        } catch (RuntimeException | LinkageError error) {
            System.getLogger(RenderModeIndicator.class.getName()).log(System.Logger.Level.WARNING,
                    "Native status badge unavailable; using JavaFX badge", error);
            return;
        }
        setVisible(false);
        placeNative();
        update();
    }

    private void closeNative() {
        if (nativeBadge == null) return;
        nativeBadge.close();
        nativeBadge = null;
        setVisible(true);
    }

    private void placeNative() {
        if (nativeBadge == null || getScene() == null) return;
        Bounds bounds = localToScene(getLayoutBounds());
        nativeBadge.place(bounds.getMinX(), getScene().getHeight() - bounds.getMaxY());
    }

    void setDeepZoom(boolean active) {
        deepZoom = active;
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
        String widest = RenderValueFormat.widestText(getText());
        String color = deepZoom ? "#e89a91" : "#7dc9e8";
        dot.setFill(Color.web(color));
        spinner.setStyle("-fx-progress-color: " + color + "; -fx-padding: 0;");
        boolean rendering = status.state() == RenderStatus.State.RENDERING;
        spinner.setVisible(rendering);
        dot.setVisible(!rendering);
        // Mode descriptions belong in Help; hover keeps only the values behind the frame.
        String values = "Zoom: " + RenderValueFormat.zoom(zoom)
                + "\nIterations: " + RenderValueFormat.iterations(iterations);
        details.setText(values);
        setAccessibleText(getText() + ". " + values.replace('\n', ' '));
        setPrefWidth(snappedLeftInset() + ICON_SIZE + ICON_GAP
                + Math.max(textWidth(getText()), textWidth(widest.replace('0', widestDigit())))
                + snappedRightInset());
        if (nativeBadge != null) {
            nativeBadge.update(getText(), widest, details.getText(), getAccessibleText(),
                    Integer.parseInt(color.substring(1), 16), rendering);
        }
    }

    private double textWidth(String text) {
        measurer.setFont(getFont());
        measurer.setText(text);
        return Math.ceil(measurer.getLayoutBounds().getWidth());
    }

    // Proportional JavaFX digits differ in width; reserve the widest one.
    private char widestDigit() {
        char widest = '0';
        for (char digit = '1'; digit <= '9'; digit++) {
            if (textWidth(String.valueOf(digit)) > textWidth(String.valueOf(widest))) widest = digit;
        }
        return widest;
    }

    static String formatElapsed(long nanos) {
        if (nanos < 1_000_000) return "< 1 ms";
        if (nanos < 1_000_000_000) return Math.round(nanos / 1_000_000.0) + " ms";
        return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
    }
}
