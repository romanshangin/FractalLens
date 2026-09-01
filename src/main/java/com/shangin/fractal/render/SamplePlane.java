package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalSample;

/** Backend-neutral mutable sample storage for one render frame. */
public interface SamplePlane {

    int width();
    int height();
    int maxIterations();
    int size();
    int iterations(int index);
    double smoothIterations(int index);
    boolean escaped(int index);
    double orbitTrapDistance(int index);
    void set(int index, FractalSample sample);
    void set(int x, int y, FractalSample sample);
    void setValues(
            int index,
            int iterations,
            double smoothIterations,
            boolean escaped,
            double orbitTrapDistance);

    default void copyPixelFrom(
            SamplePlane source, int sourceX, int sourceY, int targetX, int targetY
    ) {
        int sourceIndex = sourceY * source.width() + sourceX;
        int targetIndex = targetY * width() + targetX;
        setValues(targetIndex, source.iterations(sourceIndex),
                source.smoothIterations(sourceIndex), source.escaped(sourceIndex),
                source.orbitTrapDistance(sourceIndex));
    }

    default void copyRowFrom(
            SamplePlane source, int sourceY, int targetY, int xFrom, int xTo
    ) {
        for (int x = xFrom; x < xTo; x++) {
            copyPixelFrom(source, x, sourceY, x, targetY);
        }
    }

    default void copyRegionFrom(
            SamplePlane source,
            int sourceX,
            int sourceY,
            int targetX,
            int targetY,
            int regionWidth,
            int regionHeight
    ) {
        for (int y = 0; y < regionHeight; y++) {
            for (int x = 0; x < regionWidth; x++) {
                copyPixelFrom(source, sourceX + x, sourceY + y,
                        targetX + x, targetY + y);
            }
        }
    }
}
