package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.JuliaParameters;
import com.shangin.fractal.coloring.PalettePreset;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RenderJobTest {

    @Test
    void carriesFormulaIdentityAndParametersWithoutACalculator() {
        FormulaDefinition formula = FormulaDefinition.forPreset(
                FractalPreset.JULIA, OrbitTrap.CROSS);
        RenderJob job = new RenderJob(
                formula, new Viewport(0.0, 0.0, 2.4), 80, 60, 500);

        assertSame(formula, job.formula());
        assertEquals("JULIA", job.formula().id());
        assertEquals(FractalPreset.JULIA, job.formula().preset());
        assertEquals(OrbitTrap.CROSS, job.formula().orbitTrap());
        assertEquals(-0.8, job.formula().parameters().get("cReal"));
        assertEquals(0.156, job.formula().parameters().get("cImaginary"));
        assertThrows(UnsupportedOperationException.class,
                () -> job.formula().parameters().put("cReal", 0.0));
    }

    @Test
    void equivalentPresetDefinitionsHaveStableIdentity() {
        FormulaDefinition first = FormulaDefinition.forPreset(
                FractalPreset.MANDELBROT, OrbitTrap.NONE);
        FormulaDefinition second = FormulaDefinition.forPreset(
                FractalPreset.MANDELBROT, OrbitTrap.NONE);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, FormulaDefinition.forPreset(
                FractalPreset.MANDELBROT, OrbitTrap.POINT));
    }

    @Test
    void juliaSceneParametersChangeFormulaIdentityOnlyWhenValuesChange() {
        FractalScene scene = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                .withJuliaParameters(new JuliaParameters(-0.4, 0.6));
        FormulaDefinition first = FormulaDefinition.forScene(scene);
        FormulaDefinition second = FormulaDefinition.forScene(scene.withViewport(
                new Viewport(0.1, 0.2, 1.2)));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(-0.4, first.parameters().get("cReal"));
        assertEquals(0.6, first.parameters().get("cImaginary"));
        assertNotEquals(first, FormulaDefinition.forScene(scene.withJuliaParameters(
                new JuliaParameters(-0.8, 0.156))));
    }

    @Test
    void rejectsCoverageOutsideTarget() {
        assertThrows(IllegalArgumentException.class, () -> new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport(0.0, 0.0, 2.4),
                80, 60, 500, RenderPriority.center(),
                Optional.of(new RenderRegion(70, 0, 20, 10))));
    }
}
