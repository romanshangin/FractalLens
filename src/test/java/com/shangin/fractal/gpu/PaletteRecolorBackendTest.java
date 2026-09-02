package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PaletteRecolorBackendTest {
    @Test
    void nativeFailureRecolorsWholeOutputAndLatchesFallbackWithoutPackingMoreRequests() throws Exception {
        var device = new GpuDevice("test GPU", "1.1.0", 1, 1, 0, 64, 1_048_576,
                java.util.Set.of(GpuNumericCapability.FLOAT32));
        AtomicInteger attempts = new AtomicInteger(), closes = new AtomicInteger();
        GpuSession session = new GpuSession() {
            public java.util.List<GpuDevice> devices() { return java.util.List.of(device); }
            public GpuDevice selectedDevice() { return device; }
            public void checkHealth() {}
            public void close(boolean lost) { closes.incrementAndGet(); }
            public PaletteRecolorTiming recolorPalette(PaletteRecolorRequest request) {
                attempts.incrementAndGet();
                request.colors()[0] = 123;
                throw new GpuException("allocation failure", false);
            }
        };
        var coloring = new SmoothPaletteColoring(PalettePreset.FIRE.palette());
        var base = BaseColorPhaseCache.create(new FractalData(3, 1, 300), coloring);
        try (GpuRuntime runtime = ManagedGpuRuntime.open(GpuPlatform.MACOS, () -> session)) {
            var backend = new PaletteRecolorBackend(runtime, true);
            int[] colors = new int[3];
            assertFalse(backend.recolor(base, AntialiasSampleCache.Snapshot.EMPTY, coloring, colors).gpuUsed());
            assertArrayEquals(new int[]{0xff000000, 0xff000000, 0xff000000}, colors);
            assertFalse(backend.recolor(base, AntialiasSampleCache.Snapshot.EMPTY, coloring, colors).gpuUsed());
            assertEquals(1, attempts.get());
            assertEquals(1, closes.get());
            runtime.close();
            runtime.recolorPalette(() -> fail("Request preparation after close"), () -> PaletteRecolorTiming.cpu(0, 1, 1));
        }
    }

    @Test
    void cpuAndUnavailableGpuRetainParallelBaselineAndBuildOneLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Palette counted = position -> {
            calls.incrementAndGet();
            return PalettePreset.ICE.palette().color(position);
        };
        SmoothPaletteColoring coloring = new SmoothPaletteColoring(counted, 0.0075, -0.37);
        FractalData data = new FractalData(65, 3, 300);
        for (int i = 0; i < data.size(); i++) {
            data.setValues(i, 30, 20.0 + i * 0.71, i % 7 != 0, Double.NaN);
        }
        BaseColorPhaseCache base = BaseColorPhaseCache.create(data, coloring);
        AntialiasSampleCache aa = new AntialiasSampleCache();
        aa.put(17, new FractalSample[]{new FractalSample(27, true, 4, 1),
                new FractalSample(300, false, 0, 0)}, coloring);
        var snapshot = aa.snapshotFor(coloring);
        int[] expected = new int[data.size()];
        SmoothColorLookup lookup = new SmoothColorLookup(coloring);
        base.recolorInto(expected, lookup);
        snapshot.recolorInto(expected, lookup);
        for (boolean enabled : new boolean[]{false, true}) {
            calls.set(0);
            try (GpuRuntime runtime = new UnavailableGpuRuntime(GpuPlatform.MACOS, "no native library")) {
                int[] actual = new int[data.size()];
                var timing = new PaletteRecolorBackend(runtime, enabled).recolor(base, snapshot, coloring, actual);
                assertArrayEquals(expected, actual);
                assertEquals(65536, calls.get(), "The CPU baseline must build its lookup only once");
                assertFalse(timing.gpuUsed());
                assertEquals(0, timing.uploadedBytes());
                assertTrue(timing.totalNanos() >= timing.preparationNanos() + timing.dispatchNanos());
                Thread.currentThread().interrupt();
                try {
                    assertThrows(InterruptedException.class, () ->
                            new PaletteRecolorBackend(runtime, enabled).recolor(base, snapshot, coloring, actual));
                } finally { Thread.interrupted(); }
            }
        }
    }

    @Test
    void integerOffsetMappingMatchesEveryPhaseAtRoundingAndWrappingBoundaries() {
        double[] special = {0, 1, 2, -2, 0.5, -0.5, 0.37, 1.371, -7.219,
                Math.nextDown(1.0), Math.nextUp(1.0), Math.nextDown(2.0),
                Math.nextUp(0.0), -Math.ulp(1.0), 999999.12345, -999999.12345};
        for (double offset : special) verifyOffset(offset);
        Random random = new Random(8202);
        for (int i = 0; i < 100; i++) verifyOffset(random.nextDouble(-100, 100));
    }

    private static void verifyOffset(double offset) {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 1, offset);
        var address = PaletteOffset.from(coloring);
        for (int phase = 0; phase < 65536; phase++) {
            int expected = GradientPalette.lookupIndex(
                    coloring.palettePositionFromBasePhase(SmoothColorLookup.decode(phase)));
            int actual = address.index(phase);
            if (expected != actual) fail("offset=" + offset + " phase=" + phase
                    + " expected=" + expected + " actual=" + actual + " mapping=" + address);
        }
    }

    @Test
    void snapshotsInvalidateForAaScaleAndPanButOldSnapshotsRemainUsable() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.1, 0);
        var cache = new AntialiasSampleCache();
        cache.put(1, new FractalSample[]{new FractalSample(10, true, 4, 0)}, coloring);
        var original = cache.snapshotFor(coloring);
        assertSame(original, cache.snapshotFor(coloring));
        assertSame(AntialiasSampleCache.Snapshot.EMPTY,
                cache.snapshotFor(new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.2, 0)));
        cache.shift(8, 1, new PixelShift(1, 0));
        assertNotSame(original, cache.snapshotFor(coloring));
        assertEquals(1, original.maxPixelIndex());
        assertEquals(2, cache.snapshotFor(coloring).maxPixelIndex());
        cache.clear();
        assertEquals(1, original.size());
        assertEquals(0, cache.snapshotFor(coloring).size());
    }
}
