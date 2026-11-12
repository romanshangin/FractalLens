package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Times the readiness scans of a retained-frame return: base planning over a fully
 * ready validity mask and AA tile selection over a fully refined snapshot, each over
 * the production 32x32 tile partition. A mask missing only its final pixel is the
 * control for partial frames. Prints CSV; does not change production behavior.
 */
public final class RetainedReturnScanBenchmark {

    private static final int TILE = 32;
    private static final int WARMUPS = 2;
    private static final int SAMPLES = 5;

    public static void main(String[] args) {
        String build = args.length > 0 ? args[0] : "unlabeled";
        System.out.println("build,width,height,scenario,phase,iteration,elapsed_ns,result");
        for (int[] size : new int[][] {{1696, 1188}, {3024, 1964}}) {
            int width = size[0], height = size[1];
            List<RenderRegion> tiles = tiles(width, height);

            ValidityMask complete = new ValidityMask(width, height);
            complete.markReady(new RenderRegion(0, 0, width, height));
            ValidityMask lastPixelMissing = new ValidityMask(width, height);
            lastPixelMissing.markReady(new RenderRegion(0, 0, width, height - 1));
            lastPixelMissing.markReady(new RenderRegion(0, height - 1, width - 1, 1));
            RefinedPixelSnapshot refined = new RefinedPixelSnapshot(
                    width, height, new int[width * height], complete);

            for (int iteration = -WARMUPS; iteration < SAMPLES; iteration++) {
                String phase = iteration < 0 ? "warmup" : "sample";
                long start = System.nanoTime();
                int spans = 0;
                for (RenderRegion tile : tiles) spans += complete.missingRowSpans(tile).size();
                print(build, width, height, "base-complete", phase, iteration, System.nanoTime() - start, spans, 0);

                start = System.nanoTime();
                int unrefined = 0;
                for (RenderRegion tile : tiles) if (!refined.isRegionRefined(tile)) unrefined++;
                print(build, width, height, "aa-complete", phase, iteration, System.nanoTime() - start, unrefined, 0);

                start = System.nanoTime();
                spans = 0;
                for (RenderRegion tile : tiles) spans += lastPixelMissing.missingRowSpans(tile).size();
                print(build, width, height, "base-last-pixel-missing", phase, iteration,
                        System.nanoTime() - start, spans, 1);
            }
        }
    }

    private static void print(String build, int width, int height, String scenario, String phase,
                              int iteration, long elapsed, int result, int expected) {
        if (result != expected) {
            throw new AssertionError(scenario + " found " + result + " missing regions, expected " + expected);
        }
        System.out.println(build + "," + width + "," + height + "," + scenario + "," + phase + ","
                + iteration + "," + elapsed + "," + result);
    }

    private static List<RenderRegion> tiles(int width, int height) {
        List<RenderRegion> tiles = new ArrayList<>();
        for (int y = 0; y < height; y += TILE) {
            for (int x = 0; x < width; x += TILE) {
                tiles.add(new RenderRegion(x, y, Math.min(TILE, width - x), Math.min(TILE, height - y)));
            }
        }
        return tiles;
    }
}
