package com.shangin.fractal.coloring;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ColoringStrategyTest {

    @Test
    void colorRegionsShouldProduceSameResultAsFullColoring() throws InterruptedException {

        FractalPreset preset = FractalPreset.MANDELBROT;

        FractalCalculator calculator = new FractalCalculator(preset.createFormula());

        RenderRequest request = new RenderRequest(
                calculator,
                preset.defaultViewport(),
                100,
                70,
                300);

        List<RenderRegion> regions = new CopyOnWriteArrayList<>();

        FractalData data;

        try (ParallelFractalCalculator parallel = new ParallelFractalCalculator()) {

            data = parallel.calculate(
                    request,
                    () -> false,
                    (_, region) ->
                            regions.add(region));
        }

        assertNotNull(data);

        ColoringStrategy coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());

        FractalColorizer colorizer =
                new FractalColorizer();

        IntBuffer fullBuffer = IntBuffer.allocate(request.width() * request.height());

        IntBuffer progressiveBuffer = IntBuffer.allocate(request.width() * request.height());

        // old
        colorizer.color(data, fullBuffer, coloring);

        // new progressive
        for (RenderRegion region : regions) {
            colorizer.colorRegion(
                    data,
                    progressiveBuffer,
                    coloring,
                    region);
        }

        assertArrayEquals(fullBuffer.array(), progressiveBuffer.array());
    }
}
