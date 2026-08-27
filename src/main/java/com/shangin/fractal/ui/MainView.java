package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import javafx.scene.layout.BorderPane;

public class MainView extends BorderPane {

    private final FractalView fractalView;

    public MainView() {
        FractalPreset initialFractal = FractalPreset.MANDELBROT;
        PalettePreset initialPalette = PalettePreset.ICE;

        this.fractalView = new FractalView(initialFractal, initialPalette);

        ControlPanel controlPanel = new ControlPanel(
                initialFractal,
                initialPalette,
                fractalView::setFractal,
                fractalView::setPalette);

        setTop(controlPanel);
        setCenter(fractalView);
    }

    public void close() {
        fractalView.close();
    }
}
