package com.shangin.fractal.app;

import javafx.application.Application;

import java.util.Properties;

/** Starts the application without triggering JavaFX's Application-subclass launcher. */
public final class FractalLauncher {

    private FractalLauncher() {
    }

    public static void main(String[] args) {
        launch(FractalFxApplication.class, args);
    }

    /** Starts a JavaFX application with the same pre-toolkit policy as production. */
    public static void launch(Class<? extends Application> applicationClass, String[] args) {
        configurePrism(System.getProperties());
        Application.launch(applicationClass, args);
    }

    static void configurePrism(Properties properties) {
        if (properties.getProperty("os.name", "").startsWith("Mac")
                && properties.getProperty("prism.order") == null) {
            // Prism can fall back on initialization failure, but cannot recover from a native crash.
            properties.setProperty("prism.order", "mtl,es2,sw");
        }
    }
}
