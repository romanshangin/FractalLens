package com.shangin.fractal.render;

import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.coloring.SmoothPaletteColoring;

import java.util.stream.IntStream;

/** Compact palette-independent smooth phases for every base-frame pixel. */
public final class BaseColorPhaseCache {

    private final short[] phases;
    private final long[] escaped;
    private final double colorScale;

    private BaseColorPhaseCache(short[] phases, long[] escaped, double colorScale) {
        this.phases = phases;
        this.escaped = escaped;
        this.colorScale = colorScale;
    }

    public static BaseColorPhaseCache create(
            SamplePlane data,
            SmoothPaletteColoring coloring
    ) {
        short[] phases = new short[data.size()];
        long[] escaped = new long[(data.size() + Long.SIZE - 1) / Long.SIZE];
        IntStream.range(0, escaped.length).parallel().forEach(wordIndex -> {
            int from = wordIndex * Long.SIZE;
            int to = Math.min(data.size(), from + Long.SIZE);
            long ready = 0L;
            for (int index = from; index < to; index++) {
                if (data.escaped(index)) {
                    phases[index] = SmoothColorLookup.encode(
                            coloring.basePhase(data.smoothIterations(index)));
                    ready |= 1L << (index - from);
                }
            }
            escaped[wordIndex] = ready;
        });
        return new BaseColorPhaseCache(phases, escaped, coloring.colorScale());
    }

    public boolean matches(SmoothPaletteColoring coloring) {
        return Double.compare(colorScale, coloring.colorScale()) == 0;
    }

    public void recolorInto(int[] colors, SmoothColorLookup lookup) {
        IntStream.range(0, escaped.length).parallel().forEach(wordIndex -> {
            int from = wordIndex * Long.SIZE;
            int to = Math.min(phases.length, from + Long.SIZE);
            long ready = escaped[wordIndex];
            for (int index = from; index < to; index++) {
                colors[index] = (ready & 1L << (index - from)) != 0
                        ? lookup.color(phases[index])
                        : 0xFF000000;
            }
        });
    }
}
