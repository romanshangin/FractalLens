package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneHistoryTest {
    private final FractalScene initial = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);

    @Test void restoresCompleteSceneAndExactDecimals() {
        var history = new SceneHistory(initial);
        var next = initial.withFractal(FractalPreset.JULIA,
                new Viewport("-0.743643887037151123456789", "0.131825904205330987654321", "1e-80"))
                .withIterations(new IterationSettings(400, 70))
                .withJuliaParameters(new JuliaParameters(-0.4, 0.6))
                .withColoring(new ColoringSettings(PalettePreset.FIRE))
                .withAntialiasing(new AntialiasSettings(SamplingPattern.DETERMINISTIC_JITTER, InteractiveRenderMode.FAST));
        history.record(next);
        assertEquals(initial, history.undo());
        assertEquals(next, history.redo());
        assertEquals(next, FractalSceneJson.read(FractalSceneJson.write(next)));
    }

    @Test void duplicateAndTransientUpdatesDoNotAddActionsOrClearRedo() {
        var history = new SceneHistory(initial);
        history.record(initial);
        assertFalse(history.canUndo());
        var next = initial.withIterations(new IterationSettings(400, 70));
        history.record(next);
        history.record(next);
        assertEquals(initial, history.undo());
        var resized = initial.withViewport(new Viewport("-0.5", "0", "2"));
        history.replaceCurrent(resized);
        assertTrue(history.canRedo());
        assertEquals(next, history.redo());
        assertEquals(resized, history.undo());
    }

    @Test void newActionAfterUndoDiscardsRedo() {
        var history = new SceneHistory(initial);
        history.record(initial.withIterations(new IterationSettings(400, 70)));
        history.undo();
        var branch = initial.withColoring(new ColoringSettings(PalettePreset.FIRE));
        history.record(branch);
        assertFalse(history.canRedo());
        assertEquals(branch, history.redo());
        assertEquals(initial, history.undo());
        assertEquals(initial, history.undo());
    }

    @Test void retainsAtMostOneHundredActions() {
        var history = new SceneHistory(initial);
        for (int i = 1; i <= 110; i++) history.record(initial.withIterations(new IterationSettings(i, 0)));
        int count = 0;
        FractalScene oldest = null;
        while (history.canUndo()) { oldest = history.undo(); count++; }
        assertEquals(100, count);
        assertEquals(10, oldest.iterations().baseIterations());
    }
}
