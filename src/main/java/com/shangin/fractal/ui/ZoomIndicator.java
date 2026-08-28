package com.shangin.fractal.ui;

import javafx.geometry.Pos;
import javafx.scene.control.TextField;

import java.util.Locale;

public final class ZoomIndicator extends TextField {

    public ZoomIndicator() {
        setEditable(false);
        setFocusTraversable(true);

        setAlignment(Pos.CENTER_RIGHT);

        setPrefWidth(180);
        setMinWidth(150);
        setMaxWidth(220);

        setText(formatZoom(1.0));

        /*
         * Один клик выделяет значение целиком.
         * После этого Cmd+C / Ctrl+C сразу копирует его.
         */
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                selectAll();
            }
        });

        getStyleClass().add("zoom-indicator");
    }

    private static String formatZoom(double zoomFactor) {
        if (zoomFactor < 10.0) {
            return String.format(Locale.ROOT, "Zoom ×%.2f", zoomFactor);
        }

        if (zoomFactor < 1_000.0) {
            return String.format(Locale.ROOT, "Zoom ×%.1f", zoomFactor);
        }

        if (zoomFactor < 1_000_000.0) {
            return String.format(Locale.ROOT, "Zoom ×%.0f", zoomFactor);
        }

        return String.format(Locale.ROOT, "Zoom ×%.6e", zoomFactor);
    }

    public void setZoom(double zoomFactor) {
        if (!Double.isFinite(zoomFactor) || zoomFactor <= 0.0) {
            return;
        }

        setText(formatZoom(zoomFactor));
    }
}