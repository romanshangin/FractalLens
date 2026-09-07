package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.List;

/** Isolates planning on a fully reused frame; does not change production behavior. */
public final class ValidityScanBenchmark {
    public static void main(String[] args) {
        System.out.println("# Java=" + System.getProperty("java.version"));
        System.out.println("width,height,phase,iteration,path,elapsed_ns,missing_spans");
        for (int[] size : new int[][] {{1200, 760}, {2400, 1520}}) {
            var mask = new ValidityMask(size[0], size[1]);
            mask.markReady(new RenderRegion(0, 0, size[0], size[1]));
            List<RenderRegion> tiles = new ArrayList<>();
            for (int y = 0; y < size[1]; y += 32) {
                for (int x = 0; x < size[0]; x += 32) {
                    tiles.add(new RenderRegion(x, y, Math.min(32, size[0] - x), Math.min(32, size[1] - y)));
                }
            }
            for (int i = -2; i < 5; i++) {
                // Alternate order. Both paths must report no missing work.
                for (int order = 0; order < 2; order++) {
                    boolean guard = ((i + order) & 1) != 0;
                    long start = System.nanoTime();
                    int missing = 0;
                    if (!guard || !mask.isComplete()) {
                        for (RenderRegion tile : tiles) missing += mask.missingRowSpans(tile).size();
                    }
                    long elapsed = System.nanoTime() - start;
                    if (missing != 0) throw new AssertionError("Fully ready frame has missing samples");
                    System.out.println(size[0] + "," + size[1] + "," + (i < 0 ? "warmup" : "sample")
                            + "," + i + "," + (guard ? "diagnostic-complete-guard" : "production-tile-scan")
                            + "," + elapsed + "," + missing);
                }
            }
        }
    }
}
