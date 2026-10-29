package com.shangin.fractal.app;

/** Preserves the original main-class name without starting JavaFX before bootstrap. */
public final class FractalApplication {
    private FractalApplication() {
    }

    public static void main(String[] args) {
        FractalLauncher.main(args);
    }
}
