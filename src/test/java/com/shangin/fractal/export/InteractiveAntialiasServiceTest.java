package com.shangin.fractal.export;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.SamplingPattern;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveAntialiasServiceTest {

    @Test
    void refinementShouldReturnCurrentFrameColorsWithoutChangingSamples() throws Exception {
        FractalPreset preset = FractalPreset.MANDELBROT;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(preset.createFormula()),
                preset.defaultViewport(),
                48,
                32,
                200
        ));

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2);
             InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            calculator.calculate(frame, () -> false, ignored -> {});
            int readyPixels = frame.validity().readyPixelCount();
            int[] baseColors = AdaptivePngExportService.colorBaseFrame(
                    frame.fractalData(),
                    new SmoothPaletteColoring(PalettePreset.ICE.palette())
            );
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<int[]> result = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();

            service.refine(
                    frame,
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    SamplingPattern.REGULAR,
                    Runnable::run,
                    colors -> {
                        result.set(colors);
                        completed.countDown();
                    },
                    exception -> {
                        error.set(exception);
                        completed.countDown();
                    }
            );

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                completed.await();
            });
            assertNull(error.get());
            assertNotNull(result.get());
            assertEquals(baseColors.length, result.get().length);
            assertFalse(Arrays.equals(baseColors, result.get()));
            assertEquals(readyPixels, frame.validity().readyPixelCount());
        }
    }
}
