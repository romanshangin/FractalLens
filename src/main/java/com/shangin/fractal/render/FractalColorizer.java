package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;

import java.nio.IntBuffer;

/** Converts calculated fractal samples into packed ARGB pixels. */
public class FractalColorizer {

    public void color(
            FractalData data,
            IntBuffer buffer,
            ColoringStrategy coloring
    ) {
        for (int index = 0; index < data.size(); index++) {
            int color = coloring.color(
                    data.iterations(index),
                    data.smoothIterations(index),
                    data.escaped(index),
                    data.maxIterations());

            buffer.put(index, color);
        }
    }

    public void colorRegion(
            FractalData data,
            IntBuffer buffer,
            ColoringStrategy coloring,
            RenderRegion region
    ) {
        int xTo = region.x() + region.width();

        int yTo = region.y() + region.height();

        for (int y = region.y(); y < yTo; y++) {
            for (int x = region.x(); x < xTo; x++) {
                int index = y * data.width() + x;
                int color = coloring.color(
                        data.iterations(index),
                        data.smoothIterations(index),
                        data.escaped(index),
                        data.maxIterations());
                buffer.put(index, color);
            }
        }
    }
}
