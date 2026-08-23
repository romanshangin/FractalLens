package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;

import java.nio.IntBuffer;

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
}
