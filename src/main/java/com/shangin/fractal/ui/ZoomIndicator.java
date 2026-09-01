package com.shangin.fractal.ui;

import javafx.geometry.Pos;
import javafx.scene.control.TextField;

import java.math.BigDecimal;

/** Read-only, copyable text field showing the current zoom factor. */
public final class ZoomIndicator extends TextField {

    public ZoomIndicator() {
        setEditable(false);
        setFocusTraversable(true);

        setAlignment(Pos.CENTER_RIGHT);

        /* Let the inspector grid allocate this field exactly like Re and Im. */
        setMaxWidth(Double.MAX_VALUE);

        setText(ZoomFormat.format(BigDecimal.ONE));

        /* Select the complete value with one click for immediate copying. */
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                selectAll();
            }
        });

        getStyleClass().add("zoom-indicator");
    }

    public void setZoom(BigDecimal zoomFactor) {
        if (zoomFactor == null || zoomFactor.signum() <= 0) {
            return;
        }

        setText(ZoomFormat.format(zoomFactor));
    }
}
