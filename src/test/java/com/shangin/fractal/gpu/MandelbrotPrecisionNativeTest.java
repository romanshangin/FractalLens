package com.shangin.fractal.gpu;

import com.shangin.fractal.formula.MandelbrotFormula;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.PixelShift;
import com.shangin.fractal.render.RenderGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Fails without a real device; CPU computation is the oracle, never a substitute for dispatch. */
@EnabledIfSystemProperty(named = "fractal.gpu.fp32Native", matches = "true")
class MandelbrotPrecisionNativeTest {
    private record Scene(String name, Viewport viewport) {}
    private interface Points { double coordinate(int index, boolean imaginary); }

    @Test
    void validatesRawShaderAndIntervalAcceptanceAgainstCpu() throws Exception {
        Path file = Path.of(System.getProperty("fp32.native.output", "target/mandelbrot-fp32-native.csv"));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (var probe = VulkanMandelbrotProbe.open(); var writer = Files.newBufferedWriter(file)) {
            writer.write("# " + probe.description() + "\n");
            writer.write("# Java=" + System.getProperty("java.version") + "; OS=" + System.getProperty("os.version")
                    + "; arch=" + System.getProperty("os.arch") + "; LWJGL=3.4.2; FFM; full="
                    + Boolean.getBoolean("fp32.native.full") + "\n");
            writer.write("scene,width,height,max_iterations,points,grid_allowed,raw_escaped_mismatches,raw_smooth_failures,accepted,rejected,false_accepts,max_accepted_smooth_error,wall_ms\n");
            for (Scene scene : scenes()) {
                for (int limit : new int[]{32, 64, 300, 1000}) {
                    runGrid(probe, writer, scene.name, RenderGrid.from(scene.viewport, 384, 256), 384, 256, limit);
                }
            }
            var pan = RenderGrid.from(new Viewport(-0.75, 0, 2.4), 385, 257);
            for (PixelShift shift : List.of(new PixelShift(17, -13), new PixelShift(-39, 28))) {
                runGrid(probe, writer, "panned-" + shift.dx() + "-" + shift.dy(), pan.shifted(shift), 385, 257, 300);
            }
            double[][] boundary = boundaryPoints();
            runPoints(probe, writer, "cardioid-bulb-boundaries", 0, 0, boundary[0].length, 1000, true,
                    (i, imaginary) -> boundary[imaginary ? 1 : 0][i]);
            Random random = new Random(0x46503332L);
            double[][] randomPoints = new double[2][65539];
            for (int i = 0; i < randomPoints[0].length; i++) {
                randomPoints[0][i] = random.nextDouble(-2.5, 1.0);
                randomPoints[1][i] = random.nextDouble(-1.5, 1.5);
            }
            runPoints(probe, writer, "seeded-random", 0, 0, randomPoints[0].length, 1000, true,
                    (i, imaginary) -> randomPoints[imaginary ? 1 : 0][i]);
            if (Boolean.getBoolean("fp32.native.full")) {
                for (Scene scene : List.of(scenes().get(2), scenes().get(4))) {
                    runGrid(probe, writer, scene.name + "-retina-full", RenderGrid.from(scene.viewport, 3024, 1964),
                            3024, 1964, 300);
                }
            }
            verifyCancellationAndReuse(probe);
        }
        // A fully torn-down loader/device/kernel must reopen and perform a new dispatch.
        try (var reopened = VulkanMandelbrotProbe.open()) {
            float[] input = new float[8];
            int[] output = new int[12];
            MandelbrotPrecisionGate.pack(input, 0, 1, 1);
            reopened.calculate(input, 1, 300, output);
            assertTrue(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        }
    }

    private static List<Scene> scenes() {
        return List.of(new Scene("exterior-control", new Viewport(1, 1, 0.2)),
                new Scene("interior-control", new Viewport(0, 0, 0.1)),
                new Scene("overview", new Viewport(-0.75, 0, 2.4)),
                new Scene("cardioid-cusp", new Viewport(0.25, 0, 0.01)),
                new Scene("seahorse-100x", new Viewport(-0.743643887037151, 0.13182590420533, 0.024)),
                new Scene("seahorse-10000x", new Viewport(-0.743643887037151, 0.13182590420533, 0.00024)),
                new Scene("coordinate-collapse", new Viewport(-0.743643887037151, 0.13182590420533, 1e-7)));
    }

    private static void runGrid(VulkanMandelbrotProbe probe, BufferedWriter writer, String name,
                                RenderGrid grid, int width, int height, int limit) throws Exception {
        runPoints(probe, writer, name, width, height, width * height, limit,
                MandelbrotPrecisionGate.supportsGrid(grid, width, height),
                (i, imaginary) -> imaginary ? grid.imaginaryAt(i / width) : grid.realAt(i % width));
    }

    private static void runPoints(VulkanMandelbrotProbe probe, BufferedWriter writer, String name,
                                  int width, int height, int count, int limit, boolean gridAllowed, Points points)
            throws Exception {
        long started = System.nanoTime();
        int escapedErrors = 0, smoothErrors = 0, accepted = 0, falseAccepts = 0;
        double maxAcceptedError = 0;
        var formula = new MandelbrotFormula();
        float[] input = new float[VulkanMandelbrotProbe.CAPACITY * VulkanMandelbrotProbe.INPUT_WORDS];
        int[] output = new int[VulkanMandelbrotProbe.CAPACITY * VulkanMandelbrotProbe.OUTPUT_WORDS];
        for (int offset = 0; offset < count; offset += VulkanMandelbrotProbe.CAPACITY) {
            int batch = Math.min(VulkanMandelbrotProbe.CAPACITY, count - offset);
            for (int i = 0; i < batch; i++) {
                MandelbrotPrecisionGate.pack(input, i, points.coordinate(offset + i, false), points.coordinate(offset + i, true));
            }
            probe.calculate(input, batch, limit, output);
            for (int i = 0; i < batch; i++) {
                var reference = formula.calculate(points.coordinate(offset + i, false), points.coordinate(offset + i, true), limit);
                var candidate = MandelbrotPrecisionGate.sample(output, i);
                if (reference.escaped() != candidate.escaped()) escapedErrors++;
                double smoothError = reference.escaped() && candidate.escaped()
                        ? Math.abs(reference.smoothIterations() - candidate.smoothIterations()) : 0;
                if (!Double.isFinite(smoothError) || smoothError > MandelbrotPrecisionGate.MAX_SMOOTH_ERROR) smoothErrors++;
                if (MandelbrotPrecisionGate.accepts(output, i, limit, gridAllowed)) {
                    accepted++;
                    maxAcceptedError = Math.max(maxAcceptedError, smoothError);
                    if (!MandelbrotPrecisionGate.conforms(reference, candidate)) {
                        falseAccepts++;
                        if (falseAccepts == 1) System.err.println("False acceptance: " + name + " pixel=" + (offset + i)
                                + " reference=" + reference + " candidate=" + candidate);
                    }
                }
            }
        }
        writer.write(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%s,%d,%d,%d,%d,%d,%.9g,%.3f%n",
                name, width, height, limit, count, gridAllowed, escapedErrors, smoothErrors, accepted,
                count - accepted, falseAccepts, maxAcceptedError, (System.nanoTime() - started) / 1e6));
        writer.flush();
        System.out.printf(Locale.ROOT, "%s %dx%d/%d: raw flags=%d, smooth=%d; accepted=%.2f%%; false accepts=%d%n",
                name, width, height, limit, escapedErrors, smoothErrors, 100.0 * accepted / count, falseAccepts);
        assertEquals(0, falseAccepts, "The interval gate accepted an incorrect sample in " + name);
        if (name.equals("exterior-control") || name.equals("interior-control")) assertEquals(count, accepted);
    }

    private static double[][] boundaryPoints() {
        double[][] result = new double[2][4096 * 8];
        for (int i = 0; i < 4096; i++) {
            double angle = i * 2 * Math.PI / 4096;
            for (int shape = 0; shape < 2; shape++) {
                double re = shape == 0 ? 0.5 * Math.cos(angle) - 0.25 * Math.cos(2 * angle) : -1 + 0.25 * Math.cos(angle);
                double im = shape == 0 ? 0.5 * Math.sin(angle) - 0.25 * Math.sin(2 * angle) : 0.25 * Math.sin(angle);
                double[] neighbors = {Math.nextDown(re), Math.nextUp(re), re - 1e-12, re + 1e-12};
                for (int n = 0; n < 4; n++) {
                    int index = i * 8 + shape * 4 + n;
                    result[0][index] = neighbors[n]; result[1][index] = im;
                }
            }
        }
        return result;
    }

    private static void verifyCancellationAndReuse(VulkanMandelbrotProbe probe) throws Exception {
        float[] input = new float[8];
        int[] output = new int[12];
        MandelbrotPrecisionGate.pack(input, 0, 2, 0);
        Arrays.fill(output, -7);
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> probe.calculate(input, 1, 3, output)); }
        finally { Thread.interrupted(); }
        assertTrue(Arrays.stream(output).allMatch(i -> i == -7));
        probe.interruptAfterSubmit = true;
        try { assertThrows(InterruptedException.class, () -> probe.calculate(input, 1, 3, output)); }
        finally { probe.interruptAfterSubmit = false; Thread.interrupted(); }
        assertTrue(Arrays.stream(output).allMatch(i -> i == -7), "Cancelled dispatch must not publish");
        probe.calculate(input, 1, 2, output);
        assertFalse(MandelbrotPrecisionGate.sample(output, 0).escaped());
        probe.calculate(input, 1, 3, output);
        assertTrue(MandelbrotPrecisionGate.sample(output, 0).escaped());
        assertEquals(2, MandelbrotPrecisionGate.sample(output, 0).iterations());
    }
}
