package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.JuliaFormula;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.FractalSceneJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PersistentFrameStoreTest {

    @TempDir
    Path directory;

    private static final FormulaDefinition MANDELBROT =
            FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE);

    private static RenderJob job(FormulaDefinition formula, Viewport viewport, int width, int height, int iterations) {
        return new RenderJob(formula, viewport, width, height, iterations);
    }

    private static RenderJob mandelbrot() {
        return job(MANDELBROT, new Viewport(-0.75, 0.1, 2.4), 40, 28, 200);
    }

    private PersistentFrameStore store() {
        return new PersistentFrameStore(directory, PersistentFrameStore.DEFAULT_MAX_BYTES, Duration.ZERO);
    }

    /** Calculates the job and stores it through a fresh store, closed to flush the write. */
    private RenderFrame calculateAndStore(RenderJob job) throws Exception {
        RenderFrame frame = RenderFrame.create(job);
        try (PersistentFrameRenderBackend backend =
                     new PersistentFrameRenderBackend(new DirectDoubleRenderBackend(), store())) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }
        assertTrue(frame.isComplete());
        return frame;
    }

    private static void assertSameSamples(RenderFrame expected, RenderFrame actual) {
        SamplePlane source = expected.samplePlane();
        SamplePlane restored = actual.samplePlane();
        assertEquals(source.size(), restored.size());
        for (int index = 0; index < source.size(); index++) {
            assertEquals(source.iterations(index), restored.iterations(index), "iterations " + index);
            assertEquals(Double.doubleToRawLongBits(source.smoothIterations(index)),
                    Double.doubleToRawLongBits(restored.smoothIterations(index)), "smooth " + index);
            assertEquals(source.escaped(index), restored.escaped(index), "escaped " + index);
            assertEquals(Double.doubleToRawLongBits(source.orbitTrapDistance(index)),
                    Double.doubleToRawLongBits(restored.orbitTrapDistance(index)), "orbit trap " + index);
        }
    }

    /** Renders through a store whose wrapped backend counts the frames it must calculate. */
    private static final class CountingBackend implements RenderBackend {
        private final AtomicInteger calculated = new AtomicInteger();
        private final RenderBackend delegate = new DirectDoubleRenderBackend();

        @Override
        public RenderFrame render(RenderFrame frame, java.util.function.BooleanSupplier cancelled,
                                  java.util.function.Consumer<RenderRegion> regionCompleted,
                                  java.util.function.Consumer<TileTimingStats> timingCompleted)
                throws InterruptedException {
            calculated.incrementAndGet();
            return delegate.render(frame, cancelled, regionCompleted, timingCompleted);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private record Restored(RenderFrame frame, int calculated, List<RenderRegion> regions) {}

    private Restored renderAgain(RenderJob job) throws Exception {
        RenderFrame frame = RenderFrame.create(job);
        CountingBackend counting = new CountingBackend();
        List<RenderRegion> regions = new ArrayList<>();
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(counting, store())) {
            backend.render(frame, () -> false, regions::add, null);
        }
        assertTrue(frame.isComplete());
        return new Restored(frame, counting.calculated.get(), regions);
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(PersistentFrameStore.SUFFIX)).toList();
        }
    }

    @Test
    void restoresIdenticalSamplesWithoutCalculating() throws Exception {
        RenderFrame source = calculateAndStore(mandelbrot());

        Restored restored = renderAgain(mandelbrot());

        assertEquals(0, restored.calculated());
        assertEquals(List.of(new RenderRegion(0, 0, 40, 28)), restored.regions());
        assertSameSamples(source, restored.frame());
    }

    @Test
    void restoresJuliaOrbitTrapDistances() throws Exception {
        FormulaDefinition julia = FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.POINT);
        RenderJob job = job(julia, new Viewport(0.0, 0.0, 3.0), 32, 24, 150);
        RenderFrame source = calculateAndStore(job);
        assertTrue(java.util.stream.IntStream.range(0, source.samplePlane().size())
                .anyMatch(index -> !Double.isNaN(source.samplePlane().orbitTrapDistance(index))));

        Restored restored = renderAgain(job);

        assertEquals(0, restored.calculated());
        assertSameSamples(source, restored.frame());
    }

    @Test
    void restoresDeepZoomFramesOnTheirExactDecimalGrid() throws Exception {
        RenderJob job = job(MANDELBROT, new Viewport(
                "-0.743643887037158704752191506114774",
                "0.131825904205311970493132056385139",
                "1E-24"), 24, 16, 1200);
        RenderFrame source = RenderFrame.create(job);
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(
                new MandelbrotPerturbationRenderBackend(), store())) {
            backend.render(source, () -> false, ignored -> {}, null);
        }

        RenderFrame restored = RenderFrame.create(job);
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(
                new FailingBackend(), store())) {
            backend.render(restored, () -> false, ignored -> {}, null);
        }

        assertTrue(restored.isComplete());
        assertSameSamples(source, restored);
    }

    @Test
    void renderServicePublishesARestoredFrameAsOneCompletedRender() throws Exception {
        RenderFrame source = calculateAndStore(mandelbrot());
        RenderFrame restored = RenderFrame.create(mandelbrot());
        List<RenderRegion> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CompletableFuture<RenderFrame> completed = new java.util.concurrent.CompletableFuture<>();

        try (FractalRenderService service = new FractalRenderService(
                new PersistentFrameRenderBackend(new FailingBackend(), store()))) {
            service.render(restored, Runnable::run,
                    batch -> progress.addAll(batch.regions()),
                    completed::complete,
                    completed::completeExceptionally);
            assertSame(restored, completed.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertEquals(List.of(new RenderRegion(0, 0, 40, 28)), progress);
        assertSameSamples(source, restored);
    }

    @Test
    void missesWhenAnyCalculationInputDiffers() throws Exception {
        calculateAndStore(mandelbrot());
        FormulaDefinition otherJulia = FormulaDefinition.forScene(com.shangin.fractal.scene.FractalScene
                .create(FractalPreset.JULIA, com.shangin.fractal.coloring.PalettePreset.values()[0])
                .withJuliaParameters(new com.shangin.fractal.scene.JuliaParameters(-0.7, 0.27)));

        List<RenderJob> different = List.of(
                job(MANDELBROT, new Viewport(-0.75, 0.1, 2.4), 40, 28, 201),
                job(MANDELBROT, new Viewport(-0.75, 0.1, 2.5), 40, 28, 200),
                job(MANDELBROT, new Viewport(-0.75, 0.2, 2.4), 40, 28, 200),
                job(MANDELBROT, new Viewport(-0.75, 0.1, 2.4), 42, 28, 200),
                job(FormulaDefinition.forPreset(FractalPreset.BURNING_SHIP, OrbitTrap.NONE),
                        new Viewport(-0.75, 0.1, 2.4), 40, 28, 200),
                job(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.CROSS),
                        new Viewport(-0.75, 0.1, 2.4), 40, 28, 200),
                job(otherJulia, new Viewport(-0.75, 0.1, 2.4), 40, 28, 200),
                new RenderJob(MANDELBROT, new Viewport(-0.75, 0.1, 2.4), 40, 28, 200,
                        RenderPriority.center(), Optional.empty(), SampleAccuracy.CERTIFIED_FP32));
        for (RenderJob job : different) {
            assertEquals(1, renderAgain(job).calculated(), PersistentFrameStore.keyText(job));
        }
    }

    @Test
    void storesEveryVisitedKeyWithoutRewritingAnExistingEntry() throws Exception {
        calculateAndStore(mandelbrot());
        Path file = storedFiles().getFirst();
        FileTime old = FileTime.from(Instant.now().minusSeconds(3600));
        Files.setLastModifiedTime(file, old);

        renderAgain(mandelbrot());

        assertEquals(1, storedFiles().size());
        assertTrue(Files.getLastModifiedTime(file).compareTo(old) > 0, "a restored entry becomes most recent");
    }

    @Test
    void discardsACorruptedPayloadAndCalculatesInstead() throws Exception {
        RenderFrame source = calculateAndStore(mandelbrot());
        Path file = storedFiles().getFirst();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long position = channel.size() - 5;
            ByteBuffer value = ByteBuffer.allocate(1);
            channel.read(value, position);
            value.put(0, (byte) (value.get(0) ^ 0x40)).rewind();
            channel.write(value, position);
        }

        Restored restored = renderAgain(mandelbrot());

        assertEquals(1, restored.calculated());
        assertSameSamples(source, restored.frame());
        assertEquals(1, storedFiles().size(), "the recalculated frame is stored again");
        assertEquals(0, renderAgain(mandelbrot()).calculated());
    }

    @Test
    void discardsTruncatedAndForeignFiles() throws Exception {
        calculateAndStore(mandelbrot());
        Path file = storedFiles().getFirst();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() / 2);
        }
        assertEquals(1, renderAgain(mandelbrot()).calculated());

        Files.write(file, "not a frame".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, renderAgain(mandelbrot()).calculated());
        assertTrue(Files.size(file) > 100, "the foreign file was replaced by a valid entry");
    }

    @Test
    void discardsEntriesOfAnotherCalculationVersion() throws Exception {
        calculateAndStore(mandelbrot());
        Path file = storedFiles().getFirst();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(0, PersistentFrameStore.CALCULATION_VERSION + 1), 8 + Integer.BYTES);
        }

        assertEquals(1, renderAgain(mandelbrot()).calculated());
    }

    @Test
    void doesNotStoreFramesCalculatedOnAShiftedGrid() throws Exception {
        RenderJob job = mandelbrot();
        RenderFrame shifted = RenderFrame.create(job,
                RenderGrid.from(job.viewport(), job.width(), job.height()).shifted(new PixelShift(1, 0)));
        try (PersistentFrameRenderBackend backend =
                     new PersistentFrameRenderBackend(new DirectDoubleRenderBackend(), store())) {
            backend.render(shifted, () -> false, ignored -> {}, null);
        }

        assertTrue(shifted.isComplete());
        assertEquals(List.of(), storedFiles());
    }

    @Test
    void aNavigatedDeepViewHitsAfterItsSceneIsRestoredFromJson() throws Exception {
        FractalScene scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.values()[0]);
        Viewport viewport = new Viewport(
                "-0.743643887037158704752191506114774", "0.131825904205311970493132056385139", "3E-12");
        for (int step = 0; step < 6; step++) {
            viewport = viewport.zoomAt(17.0 + step, 9.0, 32, 20, 0.37).pan(1.5E-15, -0.7E-15);
        }
        FractalScene navigated = scene.withViewport(viewport);
        FractalScene restored = FractalSceneJson.read(FractalSceneJson.write(navigated));
        RenderJob saved = job(MANDELBROT, navigated.viewport(), 32, 20, 900);
        RenderJob reopened = job(MANDELBROT, restored.viewport(), 32, 20, 900);

        assertEquals(PersistentFrameStore.keyText(saved), PersistentFrameStore.keyText(reopened));
        RenderFrame source = RenderFrame.create(saved);
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(
                new MandelbrotPerturbationRenderBackend(), store())) {
            backend.render(source, () -> false, ignored -> {}, null);
        }
        RenderFrame frame = RenderFrame.create(reopened);
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(new FailingBackend(), store())) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }
        assertSameSamples(source, frame);
    }

    @Test
    void framesWithReusedPixelsAreCalculatedByTheWrappedBackend() throws Exception {
        calculateAndStore(mandelbrot());
        RenderFrame partial = RenderFrame.create(mandelbrot());
        partial.validity().markReady(new RenderRegion(0, 0, 4, 4));
        CountingBackend counting = new CountingBackend();

        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(counting, store())) {
            backend.render(partial, () -> false, ignored -> {}, null);
        }

        assertEquals(1, counting.calculated.get());
    }

    @Test
    void cancellationLeavesTheFrameEmptyAndUnstored() throws Exception {
        calculateAndStore(mandelbrot());
        RenderFrame cancelled = RenderFrame.create(mandelbrot());
        CountingBackend counting = new CountingBackend();

        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(counting, store())) {
            backend.render(cancelled, () -> true, ignored -> {}, null);
        }

        assertEquals(0, cancelled.validity().readyPixelCount());
        assertEquals(0, counting.calculated.get());
    }

    @Test
    void writesOnlyTheFrameThatSettles() throws Exception {
        List<RenderJob> gesture = List.of(
                job(MANDELBROT, new Viewport(-0.70, 0.1, 2.4), 24, 16, 100),
                job(MANDELBROT, new Viewport(-0.71, 0.1, 2.4), 24, 16, 100),
                job(MANDELBROT, new Viewport(-0.72, 0.1, 2.4), 24, 16, 100));
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(
                new DirectDoubleRenderBackend(),
                new PersistentFrameStore(directory, PersistentFrameStore.DEFAULT_MAX_BYTES, Duration.ofHours(1)))) {
            for (RenderJob job : gesture) {
                backend.render(RenderFrame.create(job), () -> false, ignored -> {}, null);
            }
            assertEquals(List.of(), storedFiles(), "nothing is written before the settle delay");
        }

        // Closing writes the pending frame instead of waiting for its delay.
        try (PersistentFrameStore store = store()) {
            assertEquals(List.of(store.fileFor(gesture.getLast())), storedFiles());
        }
    }

    @Test
    void evictsTheLeastRecentlyUsedEntriesBeyondTheBudget() throws Exception {
        List<RenderJob> jobs = List.of(
                job(MANDELBROT, new Viewport(-0.70, 0.1, 2.4), 24, 16, 100),
                job(MANDELBROT, new Viewport(-0.71, 0.1, 2.4), 24, 16, 100),
                job(MANDELBROT, new Viewport(-0.72, 0.1, 2.4), 24, 16, 100));
        calculateAndStore(jobs.getFirst());
        long entryBytes = Files.size(storedFiles().getFirst());
        // Distinct times per entry: eviction order among equal times is unspecified, and
        // file systems differ in timestamp resolution and directory listing order.
        Instant start = Instant.now().minusSeconds(100);
        for (int index = 0; index < jobs.size(); index++) {
            PersistentFrameStore store = new PersistentFrameStore(directory, 2 * entryBytes + 10, Duration.ZERO);
            try (PersistentFrameRenderBackend backend =
                         new PersistentFrameRenderBackend(new DirectDoubleRenderBackend(), store)) {
                backend.render(RenderFrame.create(jobs.get(index)), () -> false, ignored -> {}, null);
            }
            Files.setLastModifiedTime(store.fileFor(jobs.get(index)),
                    FileTime.from(start.plusSeconds(10L * index)));
        }

        try (PersistentFrameStore store = store()) {
            assertEquals(java.util.Set.of(store.fileFor(jobs.get(1)), store.fileFor(jobs.get(2))),
                    java.util.Set.copyOf(storedFiles()));
        }
    }

    @Test
    void skipsFramesLargerThanTheWholeBudget() throws Exception {
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(
                new DirectDoubleRenderBackend(), new PersistentFrameStore(directory, 1024, Duration.ZERO))) {
            RenderFrame frame = RenderFrame.create(mandelbrot());
            backend.render(frame, () -> false, ignored -> {}, null);
            assertTrue(frame.isComplete());
        }
        assertEquals(List.of(), storedFiles());
    }

    @Test
    void unavailableStorageFallsBackToCalculation() throws Exception {
        Path notADirectory = Files.writeString(directory.resolve("occupied"), "file");
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(
                new DirectDoubleRenderBackend(),
                new PersistentFrameStore(notADirectory.resolve("frames"),
                        PersistentFrameStore.DEFAULT_MAX_BYTES, Duration.ZERO))) {
            for (int attempt = 0; attempt < 2; attempt++) {
                RenderFrame frame = RenderFrame.create(mandelbrot());
                backend.render(frame, () -> false, ignored -> {}, null);
                assertTrue(frame.isComplete());
            }
        }
    }

    @Test
    void customFormulasAreNeitherStoredNorRestored() throws Exception {
        RenderJob job = job(FormulaDefinition.custom(new JuliaFormula(-0.8, 0.156), OrbitTrap.NONE),
                new Viewport(0.0, 0.0, 3.0), 24, 16, 100);
        calculateAndStore(job);

        assertEquals(List.of(), storedFiles());
    }

    @Test
    void closeIsIdempotentAndLeavesNoTemporaryFiles() throws Exception {
        PersistentFrameStore store = new PersistentFrameStore(
                directory, PersistentFrameStore.DEFAULT_MAX_BYTES, Duration.ofHours(1));
        RenderFrame frame = RenderFrame.create(mandelbrot());
        try (DirectDoubleRenderBackend backend = new DirectDoubleRenderBackend()) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }
        store.saveWhenSettled(frame);

        store.close();
        store.close();
        store.saveWhenSettled(frame);

        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(store.fileFor(mandelbrot())), files.toList());
        }
    }

    @Test
    void removesStaleTemporaryFilesButSparesRecentOnes() throws Exception {
        Path stale = Files.writeString(directory.resolve("a.frame.1.1.tmp"), "partial");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        Path recent = Files.writeString(directory.resolve("b.frame.2.1.tmp"), "in progress");

        store().close();

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(recent));
    }

    @Test
    void keyKeepsExactDecimalDigits() {
        RenderJob first = job(MANDELBROT, new Viewport("-0.75", "0.1", "2.4"), 24, 16, 100);
        RenderJob second = job(MANDELBROT, new Viewport("-0.750000000000000000000000000001", "0.1", "2.4"),
                24, 16, 100);

        assertNotEquals(PersistentFrameStore.keyText(first), PersistentFrameStore.keyText(second));
        assertEquals(PersistentFrameStore.keyText(first), PersistentFrameStore.keyText(
                job(MANDELBROT, new Viewport("-0.75", "0.1", "2.4"), 24, 16, 100)));
        assertTrue(PersistentFrameStore.keyText(first).contains("centerReal=-0.75\n"));
        assertEquals(Map.of(), MANDELBROT.parameters());
    }

    /** A wrapped backend that must never be asked to calculate. */
    private static final class FailingBackend implements RenderBackend {
        @Override
        public RenderFrame render(RenderFrame frame, java.util.function.BooleanSupplier cancelled,
                                  java.util.function.Consumer<RenderRegion> regionCompleted,
                                  java.util.function.Consumer<TileTimingStats> timingCompleted) {
            throw new AssertionError("The stored frame should have been restored");
        }

        @Override
        public void close() {}
    }
}
