package com.shangin.fractal.export;

import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalData;
import com.shangin.fractal.render.ParallelFractalCalculator;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePngExportServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void detectsMembershipAndColorEdges() {
        FractalData data = new FractalData(3, 1, 100);
        data.set(0, new FractalSample(100, false, 0.0, 0.0));
        data.set(1, new FractalSample(100, false, 0.0, 0.0));
        data.set(2, new FractalSample(10, true, 3.0, 0.0));

        int[] colors = {0xFF000000, 0xFF000000, 0xFFFFFFFF};

        assertFalse(AdaptivePngExportService.isBaseEdge(data, colors, 0, 0));
        assertTrue(AdaptivePngExportService.isBaseEdge(data, colors, 1, 0));
        assertTrue(AdaptivePngExportService.isBaseEdge(data, colors, 2, 0));
    }

    @Test
    void ditheringIsStableAndLimitedToOneChannelStep() {
        int source = 0xFF808080;
        Set<Integer> results = new HashSet<>();

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int first = AdaptivePngExportService.dither(source, x, y);
                int second = AdaptivePngExportService.dither(source, x, y);

                assertEquals(first, second);
                assertTrue(Math.abs(((first >>> 16) & 0xFF) - 128) <= 1);
                assertEquals((first >>> 16) & 0xFF, (first >>> 8) & 0xFF);
                assertEquals((first >>> 8) & 0xFF, first & 0xFF);
                results.add(first);
            }
        }

        assertTrue(results.size() > 1);
    }

    @Test
    void exportsCompletedFrameAtOriginalDimensions() throws Exception {
        int width = 24;
        int height = 16;
        FractalPreset preset = FractalPreset.JULIA;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(preset.createFormula()),
                preset.defaultViewport(),
                width,
                height,
                120
        ));

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2)) {
            calculator.calculate(frame, () -> false, ignored -> {});
        }

        Path target = tempDirectory.resolve("adaptive.png");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        try (AdaptivePngExportService service = new AdaptivePngExportService()) {
            service.export(
                    frame,
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    target,
                    ignored -> completed.countDown(),
                    exception -> {
                        error.set(exception);
                        completed.countDown();
                    }
            );

            assertTrue(completed.await(10, TimeUnit.SECONDS));
        }

        assertNull(error.get());

        BufferedImage image = ImageIO.read(target.toFile());
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }
}
