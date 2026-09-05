package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;

import java.nio.IntBuffer;
import java.util.BitSet;

/** Converts calculated fractal samples into packed ARGB pixels. */
public class FractalColorizer {

    public void color(
            SamplePlane data,
            IntBuffer buffer,
            ColoringStrategy coloring
    ) {
        for (int index = 0; index < data.size(); index++) {
            int color = coloring.color(
                    data.iterations(index),
                    data.smoothIterations(index),
                    data.escaped(index),
                    data.maxIterations(),
                    data.orbitTrapDistance(index));

            buffer.put(index, color);
        }
    }

    public void colorRegion(
            SamplePlane data,
            IntBuffer buffer,
            ColoringStrategy coloring,
            RenderRegion region
    ) {
        colorRegion(data, buffer, coloring, region, null);
    }

    /** Tile progress may straddle reused AA pixels along a resized edge. */
    public void colorRegion(SamplePlane data, IntBuffer buffer, ColoringStrategy coloring,
                            RenderRegion region, ValidityMask preserve) {
        BitSet preserved = preserve == null ? null : preserve.readyBitsCopy(region);
        if (preserved != null && preserved.isEmpty()) preserved = null;
        int xTo = region.x() + region.width();
        int yTo = region.y() + region.height();

        if (preserved == null) {
            for (int y = region.y(); y < yTo; y++) {
                for (int x = region.x(); x < xTo; x++) {
                    int index = y * data.width() + x;
                    buffer.put(index, coloring.color(
                            data.iterations(index), data.smoothIterations(index), data.escaped(index),
                            data.maxIterations(), data.orbitTrapDistance(index)));
                }
            }
            return;
        }

        for (int y = region.y(); y < yTo; y++) {
            for (int x = region.x(); x < xTo; x++) {
                int index = y * data.width() + x;
                int localIndex = (y - region.y()) * region.width() + x - region.x();
                if (preserved.get(localIndex)) continue;
                int color = coloring.color(
                        data.iterations(index),
                        data.smoothIterations(index),
                        data.escaped(index),
                        data.maxIterations(),
                        data.orbitTrapDistance(index));
                buffer.put(index, color);
            }
        }
    }

    /**
     * Colors every ready sample except pixels whose refined colors must remain
     * untouched while a shifted frame is completed after a pan.
     */
    public void colorReadyPixels(
            SamplePlane data,
            IntBuffer buffer,
            ColoringStrategy coloring,
            ValidityMask ready,
            ValidityMask preserve
    ) {
        if (ready.width() != data.width() || ready.height() != data.height()) {
            throw new IllegalArgumentException("Ready mask dimensions do not match fractal data");
        }
        if (preserve != null
                && (preserve.width() != data.width() || preserve.height() != data.height())) {
            throw new IllegalArgumentException("Preserve mask dimensions do not match fractal data");
        }

        BitSet pixels = ready.readyBitsCopy();
        if (preserve != null) {
            pixels.andNot(preserve.readyBitsCopy());
        }

        for (int index = pixels.nextSetBit(0);
             index >= 0;
             index = pixels.nextSetBit(index + 1)) {
            buffer.put(index, coloring.color(
                    data.iterations(index),
                    data.smoothIterations(index),
                    data.escaped(index),
                    data.maxIterations(),
                    data.orbitTrapDistance(index)));
        }
    }
}
