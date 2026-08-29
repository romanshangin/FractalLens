package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class FractalSceneTest {

    @Test
    void updatesViewportWithoutMutatingOtherSceneState() {
        FractalScene source = FractalScene.create(
                FractalPreset.MANDELBROT,
                PalettePreset.ICE
        );
        Viewport viewport = new Viewport(-0.5, 0.25, 0.1);

        FractalScene updated = source.withViewport(viewport);

        assertNotSame(source, updated);
        assertEquals(viewport, updated.viewport());
        assertEquals(source.fractal(), updated.fractal());
        assertSame(source.iterations(), updated.iterations());
        assertSame(source.coloring(), updated.coloring());
    }

    @Test
    void changesColoringAsAnIndependentSceneSnapshot() {
        FractalScene source = FractalScene.create(
                FractalPreset.JULIA,
                PalettePreset.ICE
        );
        ColoringSettings coloring = new ColoringSettings(PalettePreset.FIRE);

        FractalScene updated = source.withColoring(coloring);

        assertEquals(PalettePreset.ICE, source.coloring().palette());
        assertEquals(PalettePreset.FIRE, updated.coloring().palette());
        assertEquals(source.viewport(), updated.viewport());
    }

    @Test
    void changesAntialiasingAsAnIndependentSceneSnapshot() {
        FractalScene source = FractalScene.create(
                FractalPreset.MANDELBROT,
                PalettePreset.ICE
        );

        FractalScene updated = source.withAntialiasing(
                new AntialiasSettings(SamplingPattern.DETERMINISTIC_JITTER)
        );

        assertEquals(SamplingPattern.REGULAR, source.antialiasing().samplingPattern());
        assertEquals(
                SamplingPattern.DETERMINISTIC_JITTER,
                updated.antialiasing().samplingPattern()
        );
        assertEquals(source.viewport(), updated.viewport());
    }
}
