package com.shangin.fractal.app;

import javafx.application.Application;

/** Starts the application without triggering JavaFX's Application-subclass launcher. */
public final class FractalLauncher {

    private FractalLauncher() {
    }

    public static void main(String[] args) {
        // Keep JavaFX's pipeline defaults until the Metal migration gates pass.
        Application.launch(FractalApplication.class, args);
    }
}
