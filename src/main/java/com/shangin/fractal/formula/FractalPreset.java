package com.shangin.fractal.formula;

import com.shangin.fractal.math.Viewport;

import java.util.function.Supplier;

public enum FractalPreset {

    MANDELBROT(
            "Mandelbrot",
            MandelbrotFormula::new,
            new Viewport(-0.75, 0.0, 2.4),
            3.5,
            2.4,
            16.0),

    JULIA(
            "Julia",
            () -> new JuliaFormula(-0.8, 0.156),
            new Viewport(0.0, 0.0, 2.4),
            3.0,
            2.4,
            256.0),

    MULTIBROT_CUBIC(
            "Multibrot (z³)",
            () -> new MultibrotFormula(3),
            new Viewport(0.0, 0.0, 2.4),
            3.0,
            2.4,
            16.0),

    BURNING_SHIP(
            "Burning Ship",
            BurningShipFormula::new,
            new Viewport(-0.5, 0.5, 2.5),
            3.5,
            2.5,
            16.0),

    TRICORN(
            "Tricorn",
            TricornFormula::new,
            new Viewport(0.0, 0.0, 2.5),
            3.5,
            2.5,
            16.0);

    private final String displayName;
    private final Supplier<FractalFormula> formulaSupplier;
    private final Viewport defaultViewport;
    private final double defaultWidth;
    private final double defaultHeight;
    private final double minimumUlpsPerPixel;

    FractalPreset(
            String displayName,
            Supplier<FractalFormula> formulaSupplier,
            Viewport defaultViewport,
            double defaultWidth,
            double defaultHeight,
            double minimumUlpsPerPixel
    ) {
        this.displayName = displayName;
        this.formulaSupplier = formulaSupplier;
        this.defaultViewport = defaultViewport;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
        this.minimumUlpsPerPixel = minimumUlpsPerPixel;
    }

    public FractalFormula createFormula() {
        return formulaSupplier.get();
    }

    public Viewport defaultViewport() {
        return defaultViewport;
    }

    public double defaultWidth() {
        return defaultWidth;
    }

    public double defaultHeight() {
        return defaultHeight;
    }

    /** Precision reserve required before allowing another hardware-double zoom. */
    public double minimumUlpsPerPixel() {
        return minimumUlpsPerPixel;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
