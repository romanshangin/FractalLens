package com.shangin.fractal.app;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Exercises the original main-class name with the same bootstrap smoke contract. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalCompatibilityLauncherFxTest extends FractalLauncherFxTest {
    @Override
    void launch(String[] args) {
        FractalApplication.main(args);
    }
}
