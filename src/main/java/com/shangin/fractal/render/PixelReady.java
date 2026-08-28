package com.shangin.fractal.render;

/** Tests whether a render pixel already contains reusable sample data. */
@FunctionalInterface
interface PixelReady {
    boolean test(int x, int y);
}
