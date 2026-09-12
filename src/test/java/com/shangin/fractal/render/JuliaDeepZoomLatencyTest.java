package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class JuliaDeepZoomLatencyTest {
    @Test
    @EnabledIfSystemProperty(named = "fractal.julia.latency", matches = "true")
    void measuresDeepJuliaFirstRegionAndCompletion() throws Exception {
        int width = Integer.getInteger("fractal.julia.width", 96);
        int height = Integer.getInteger("fractal.julia.height", 64);
        RenderJob job = Boolean.getBoolean("fractal.julia.reported") ? ReportedJuliaFixture.job(width, height) : new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE),
                new Viewport("-0.035", "-0.493", "1e-16"), width, height, 3000);
        AtomicLong first = new AtomicLong();
        long started = System.nanoTime();
        try (var backend = new JuliaDeepZoomRenderBackend(4)) {
            var frame = backend.render(RenderFrame.create(job), () -> false,
                    ignored -> first.compareAndSet(0, System.nanoTime()), null);
            assertTrue(frame.isComplete());
            String imagePath = System.getProperty("fractal.julia.image");
            if (imagePath != null) {
                int[] colors = new int[width * height];
                new FractalColorizer().color(frame.samplePlane(), java.nio.IntBuffer.wrap(colors),
                        new com.shangin.fractal.coloring.SmoothPaletteColoring(
                                com.shangin.fractal.coloring.PalettePreset.ICE.palette()));
                com.shangin.fractal.export.PngExporter.write(java.nio.file.Path.of(imagePath), width, height, colors);
            }
        }
        System.out.printf("JULIA %dx%d first=%.3f ms complete=%.3f ms%n", width, height,
                (first.get() - started) / 1e6, (System.nanoTime() - started) / 1e6);
    }
}
