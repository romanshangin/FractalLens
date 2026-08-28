package com.shangin.fractal.ui;

import javafx.geometry.Pos;
import javafx.scene.control.TextField;

import java.util.Locale;

/** Read-only, copyable text field showing the current zoom factor. */
public final class ZoomIndicator extends TextField {

    public ZoomIndicator() {
        setEditable(false);
        setFocusTraversable(true);

        setAlignment(Pos.CENTER_RIGHT);

        setPrefWidth(130);
        setMinWidth(100);
        setMaxWidth(180);

        setText(formatZoom(1.0));

        /* Select the complete value with one click for immediate copying. */
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                selectAll();
            }
        });

        getStyleClass().add("zoom-indicator");
    }

    private static String formatZoom(double zoomFactor) {
        if (zoomFactor < 10.0) {
            return String.format(Locale.ROOT, "%.2f", zoomFactor);
        }

        if (zoomFactor < 1_000.0) {
            return String.format(Locale.ROOT, "%.1f", zoomFactor);
        }

        if (zoomFactor < 1_000_000.0) {
            return String.format(Locale.ROOT, "%.0f", zoomFactor);
        }

        return String.format(Locale.ROOT, "%.6e", zoomFactor);
    }

    public void setZoom(double zoomFactor) {
        if (!Double.isFinite(zoomFactor) || zoomFactor <= 0.0) {
            return;
        }

        setText(formatZoom(zoomFactor));
    }
}
