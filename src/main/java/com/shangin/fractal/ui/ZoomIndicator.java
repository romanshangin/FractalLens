package com.shangin.fractal.ui;

import javafx.geometry.Pos;
import javafx.scene.control.TextField;
import javafx.scene.text.Text;

import java.math.BigDecimal;

/** Read-only, copyable text field showing the current zoom factor. */
public final class ZoomIndicator extends TextField {

    private static final double TEXT_SAFETY_MARGIN = 2.0;

    private final Text textMeasurer = new Text();
    private BigDecimal zoomFactor = BigDecimal.ONE;

    public ZoomIndicator() {
        setEditable(false);
        setFocusTraversable(true);

        setAlignment(Pos.CENTER_RIGHT);

        /* Let the inspector grid allocate this field exactly like Re and Im. */
        setMaxWidth(Double.MAX_VALUE);

        setText(ZoomFormat.format(zoomFactor));

        widthProperty().addListener((observable, oldWidth, newWidth) -> updateDisplayedText());
        fontProperty().addListener((observable, oldFont, newFont) -> updateDisplayedText());

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

        this.zoomFactor = zoomFactor;
        updateDisplayedText();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        updateDisplayedText();
    }

    private void updateDisplayedText() {
        String formatted = ZoomFormat.format(zoomFactor, this::fitsAvailableWidth);
        if (!formatted.equals(getText())) {
            setText(formatted);
        }
    }

    private boolean fitsAvailableWidth(String value) {
        double availableWidth = getWidth()
                - getInsets().getLeft()
                - getInsets().getRight()
                - TEXT_SAFETY_MARGIN;
        if (availableWidth <= 0.0) {
            return true;
        }

        textMeasurer.setFont(getFont());
        textMeasurer.setText(value);
        return Math.ceil(textMeasurer.getLayoutBounds().getWidth()) <= availableWidth;
    }
}
