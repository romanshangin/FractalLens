package com.shangin.fractal.ui;

import com.shangin.fractal.formula.*;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class FractalDestinationTest {
    @Test
    void allDestinationsHaveVisibleStructureAndSurviveNavigation() throws IOException {
        int w = 240, h = 160, columns = 4;
        byte[] pixels = new byte[w * columns * h * 5 * 3];
        int row = 0;
        for (var preset : FractalPreset.values()) {
            var destinations = FractalDestination.forPreset(preset);
            assertTrue(destinations.size() >= 3);
            var formula = preset.createFormula();
            var camera = new FractalCamera(preset);
            camera.reset(1000, 700);
            int col = 0;
            for (var destination : destinations) {
                Viewport v = destination.viewport();
                camera.zoomIn(100, 200, 1000, 700, 2000, 1400);
                camera.goTo(v, 1000, 700, 2000, 1400);
                assertEquals(v, camera.viewport());
                camera.resize(1000, 700, 1400, 1400);
                assertEquals(v, camera.viewport());
                int min = 2000, max = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    var sample = formula.calculate(v.realAt(x, w, h), v.imaginaryAt(y, h), 2000);
                    int n = sample.iterations();
                    min = Math.min(min, n); max = Math.max(max, n);
                    int offset = (((row * h + y) * w * columns) + col * w + x) * 3;
                    if (n < 2000) {
                        pixels[offset] = (byte)(128 + 127 * Math.sin(n * 0.09));
                        pixels[offset + 1] = (byte)(128 + 127 * Math.sin(n * 0.09 + 2));
                        pixels[offset + 2] = (byte)(128 + 127 * Math.sin(n * 0.09 + 4));
                    }
                }
                assertTrue(max - min > 20, preset + ": " + destination.name());
                col++;
            }
            camera.reset(1000, 700);
            assertEquals(camera.defaultViewport(1000, 700), camera.viewport());
            row++;
        }
        try (var out = Files.newOutputStream(Path.of("target/destinations.ppm"))) {
            out.write(("P6\n" + w * columns + " " + h * 5 + "\n255\n").getBytes());
            out.write(pixels);
        }
    }

    @Test
    void directPrecisionFailureDoesNotChangeCamera() {
        var camera = new FractalCamera(FractalPreset.JULIA);
        camera.reset(1000, 700);
        var before = camera.viewport();
        assertThrows(IllegalArgumentException.class, () -> camera.goTo(
                new Viewport("-0.1", "-0.45", "1e-30"), 1000, 700, 2000, 1400));
        assertEquals(before, camera.viewport());
    }
}
