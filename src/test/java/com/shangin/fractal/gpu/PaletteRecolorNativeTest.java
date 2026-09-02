package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;

/** Actual shader gate: a CPU fallback must never masquerade as a successful GPU comparison. */
@EnabledIfSystemProperty(named = "fractal.gpu.nativeSmoke", matches = "true")
class PaletteRecolorNativeTest {
    @Test
    void validatesShaderConformanceResidencyChangesAndFailureFallback() throws Exception {
        // The odd size exercises both the 16-bit packing tail and a partial workgroup.
        FractalData data = new FractalData(65539, 1, 20_001_000);
        for (int i = 0; i < data.size(); i++) {
            data.setValues(i, 20_000_001, SmoothColorLookup.decode(i & 65535), i % 11 != 0, Double.NaN);
        }
        var source = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 1, 0);
        var base = BaseColorPhaseCache.create(data, source);
        var cache = new AntialiasSampleCache();
        var aaPixels = new BitSet(data.size());
        for (int i = 0; i < data.size(); i += 17) {
            int count = switch (i % 4) { case 0 -> 1; case 1 -> 4; case 2 -> 16; default -> 32; };
            FractalSample[] samples = new FractalSample[count];
            for (int s = 0; s < count; s++) {
                samples[s] = new FractalSample(20 + (i + s) % 150, (i + s) % 5 != 0, 4.0, s * 0.3);
            }
            cache.put(i, samples, source);
            aaPixels.set(i);
        }
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
            var gpu = new PaletteRecolorBackend(runtime, true);
            var cpu = new PaletteRecolorBackend(runtime, false);
            if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) {
                assertFalse(gpu.recolor(base, cache.snapshotFor(source), source, new int[data.size()]).gpuUsed());
                return;
            }
            assertTrue(runtime.isUsableFor(GpuNumericCapability.FLOAT32), () -> runtime.capabilityReport().detail());
            int[] expected = new int[data.size()], actual = new int[data.size()];
            if (Boolean.getBoolean("fractal.gpu.expectPaletteUnavailable")) {
                assertFalse(gpu.recolor(base, cache.snapshotFor(source), source, actual).gpuUsed());
                cpu.recolor(base, cache.snapshotFor(source), source, expected);
                assertArrayEquals(expected, actual);
                assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
                return;
            }
            boolean first = true;
            for (PalettePreset palette : PalettePreset.values()) {
                for (double offset : new double[]{0, 0.37, 1.371, -0.5, 1.999999, 2.0}) {
                    var coloring = new SmoothPaletteColoring(palette.palette(), 1, offset);
                    cpu.recolor(base, cache.snapshotFor(coloring), coloring, expected);
                    var timing = gpu.recolor(base, cache.snapshotFor(coloring), coloring, actual);
                    assertTrue(timing.gpuUsed(), () -> runtime.capabilityReport().detail());
                    if (first) {
                        assertTrue(timing.uploadedBytes() > 0);
                        first = false;
                    }
                    compare(expected, actual, aaPixels);
                    assertEquals(0, gpu.recolor(base, cache.snapshotFor(coloring), coloring, actual).uploadedBytes(),
                            "Stable phases, AA and palette must remain resident across offset frames");
                }
            }
            cache.put(1, new FractalSample[]{new FractalSample(30, true, 4, 0)}, source);
            aaPixels.set(1);
            assertTrue(gpu.recolor(base, cache.snapshotFor(source), source, actual).uploadedBytes() > 0);
            cpu.recolor(base, cache.snapshotFor(source), source, expected);
            compare(expected, actual, aaPixels);
            // Color-scale and dimension changes create independent generations.
            var scaled = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.75, 0.37);
            var scaledBase = BaseColorPhaseCache.create(data, scaled);
            assertTrue(gpu.recolor(scaledBase, cache.snapshotFor(scaled), scaled, actual).uploadedBytes() > 0);
            cpu.recolor(scaledBase, cache.snapshotFor(scaled), scaled, expected);
            assertArrayEquals(expected, actual);
            FractalData small = new FractalData(13, 7, 300);
            var smallBase = BaseColorPhaseCache.create(small, source);
            int[] smallColors = new int[small.size()];
            assertTrue(gpu.recolor(smallBase, AntialiasSampleCache.Snapshot.EMPTY, source, smallColors).gpuUsed());
            for (int color : smallColors) assertEquals(0xff000000, color);
            // Loss after an actual dispatch must release kernel resources and preserve CPU output.
            runtime.handleDeviceLoss(new GpuException("injected after palette dispatch", true));
            assertFalse(gpu.recolor(base, cache.snapshotFor(source), source, actual).gpuUsed());
            cpu.recolor(base, cache.snapshotFor(source), source, expected);
            assertArrayEquals(expected, actual);
        }
        if (!Boolean.getBoolean("fractal.gpu.expectUnavailable")) {
            try (GpuRuntime reopened = GpuRuntimeFactory.createDefault()) {
                assertTrue(new PaletteRecolorBackend(reopened, true).recolor(base,
                        AntialiasSampleCache.Snapshot.EMPTY, source, new int[data.size()]).gpuUsed(),
                        () -> reopened.capabilityReport().detail());
            }
        }
    }

    private static void compare(int[] expected, int[] actual, BitSet aa) {
        int maximum = 0;
        for (int i = 0; i < expected.length; i++) {
            int tolerance = aa.get(i) ? 1 : 0;
            assertEquals(expected[i] >>> 24, actual[i] >>> 24, "alpha at " + i);
            for (int shift = 0; shift < 24; shift += 8) {
                int error = Math.abs((expected[i] >>> shift & 255) - (actual[i] >>> shift & 255));
                maximum = Math.max(maximum, error);
                if (error > tolerance) fail("Pixel " + i + " error=" + error + " tolerance=" + tolerance);
            }
        }
        assertTrue(maximum <= 1);
    }
}
