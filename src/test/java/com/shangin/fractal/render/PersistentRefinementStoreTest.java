package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.ColoringSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Antialiasing stored beside persistent base frames. */
class PersistentRefinementStoreTest {

    @TempDir
    Path directory;

    private static final FormulaDefinition MANDELBROT =
            FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE);
    private static final ColoringSettings CUSTOM = new ColoringSettings(PalettePreset.ICE,
            List.of(new ColorStop(0.0, 0xFF102030), new ColorStop(0.4, 0xFFFFAA00), new ColorStop(1.0, 0xFF000000)),
            1.7, 0.3, false, OrbitTrap.NONE);

    private static RenderJob job(double centerReal) {
        return new RenderJob(MANDELBROT, new Viewport(centerReal, 0.1, 2.4), 40, 28, 200);
    }

    private PersistentFrameStore store() {
        return store(PersistentFrameStore.DEFAULT_MAX_BYTES);
    }

    private PersistentFrameStore store(long maxBytes) {
        return new PersistentFrameStore(directory, maxBytes, Duration.ZERO);
    }

    /** Calculates the job and stores its base frame, then its AA, through one store. */
    private RenderFrame calculateAndStore(RenderJob job, ColoringSettings coloring, long maxBytes)
            throws Exception {
        RenderFrame frame = RenderFrame.create(job);
        try (PersistentFrameRenderBackend backend =
                     new PersistentFrameRenderBackend(new DirectDoubleRenderBackend(), store(maxBytes))) {
            backend.render(frame, () -> false, ignored -> {}, null);
            if (coloring != null) backend.saveRefinementWhenSettled(frame, refinement(frame, coloring));
        }
        assertTrue(frame.isComplete());
        return frame;
    }

    private RenderFrame calculateAndStore(RenderJob job, ColoringSettings coloring) throws Exception {
        return calculateAndStore(job, coloring, PersistentFrameStore.DEFAULT_MAX_BYTES);
    }

    /** AA colors on two regions and mixed palette-independent samples, as a finished pass keeps them. */
    private static RetainedRefinement refinement(RenderFrame frame, ColoringSettings coloring) {
        int width = frame.request().width(), height = frame.request().height();
        int size = width * height;
        int[] colors = new int[size];
        for (int index = 0; index < size; index++) colors[index] = 0xFF000000 | index * 7919;
        ValidityMask validity = new ValidityMask(width, height);
        validity.markReady(new RenderRegion(3, 2, 10, 5));
        validity.markReady(new RenderRegion(0, 20, width, 1));
        SmoothPaletteColoring smooth = (SmoothPaletteColoring) coloring.createStrategy();
        var cache = new AntialiasSampleCache();
        var batch = cache.newBatch(64, smooth);
        var lookup = new SmoothColorLookup(smooth);
        for (int entry = 0; entry < 40; entry++) {
            var samples = new FractalSample[entry % 2 == 0 ? 16 : 4];
            for (int sample = 0; sample < samples.length; sample++) {
                samples[sample] = new FractalSample(10 + sample + entry, (sample + entry) % 3 != 0,
                        3.0 + sample * 0.1, 0.5);
            }
            batch.add(entry * 23 % size, samples, lookup,
                    entry % 5 == 0 ? Double.POSITIVE_INFINITY : 0.01 * entry, entry % 3 == 0);
        }
        cache.merge(batch);
        return new RetainedRefinement(new RefinedPixelSnapshot(width, height, colors, validity), coloring,
                cache.retain().withCompleteThreshold(0.125));
    }

    private static void assertSameRefinement(RetainedRefinement expected, RetainedRefinement actual) {
        assertEquals(expected.coloring(), actual.coloring());
        assertTrue(expected.pixels().sameRefinedPixels(actual.pixels()), "AA colors and their validity");
        assertEquals(expected.pixels().validity().readyPixelCount(), actual.pixels().validity().readyPixelCount());
        var source = expected.samples();
        var restored = actual.samples();
        assertEquals(source.size(), restored.size());
        assertEquals(source.phaseCount(), restored.phaseCount());
        assertEquals(Double.doubleToRawLongBits(source.colorScale()), Double.doubleToRawLongBits(restored.colorScale()));
        assertEquals(source.completeThreshold(), restored.completeThreshold());
        assertEquals(source.dropped(), restored.dropped());
        // Recoloring under several palettes covers phases, masks, edge strengths and deep flags.
        for (ColoringSettings coloring : List.of(expected.coloring(),
                new ColoringSettings(PalettePreset.FIRE, expected.coloring().colorScale(), 0.0))) {
            var smooth = (SmoothPaletteColoring) coloring.createStrategy();
            int size = expected.pixels().width() * expected.pixels().height();
            int[] sourceColors = new int[size];
            int[] restoredColors = new int[size];
            var sourceSelection = source.snapshotFor(smooth);
            var restoredSelection = restored.snapshotFor(smooth);
            assertEquals(sourceSelection.size(), restoredSelection.size(), "selected entries");
            assertEquals(sourceSelection.pixelMask(), restoredSelection.pixelMask());
            sourceSelection.recolorInto(sourceColors, new SmoothColorLookup(smooth));
            restoredSelection.recolorInto(restoredColors, new SmoothColorLookup(smooth));
            assertArrayEquals(sourceColors, restoredColors);
        }
    }

    private Path refinementFile(RenderJob job) {
        try (PersistentFrameStore store = store()) {
            return PersistentFrameStore.refinementFileFor(store.fileFor(job));
        }
    }

    private Path frameFile(RenderJob job) {
        try (PersistentFrameStore store = store()) {
            return store.fileFor(job);
        }
    }

    /** Restores the base frame through a fresh store and reads its AA. */
    private RetainedRefinement restore(RenderJob job) {
        RenderFrame frame = RenderFrame.create(job);
        try (PersistentFrameStore store = store()) {
            assertTrue(store.load(frame, () -> false), "The base frame must be restored first");
            return store.loadRefinement(frame, () -> false);
        }
    }

    @Test
    void restoresStoredAntialiasingBesideItsFrame() throws Exception {
        RenderFrame source = calculateAndStore(job(-0.75), CUSTOM);
        RetainedRefinement restored = restore(job(-0.75));
        assertNotNull(restored);
        assertSameRefinement(refinement(source, CUSTOM), restored);
    }

    @Test
    void theBackendHandsOutRestoredAntialiasingOnceAndOnlyForItsFrame() throws Exception {
        RenderFrame source = calculateAndStore(job(-0.75), CUSTOM);
        RenderFrame restoredFrame = RenderFrame.create(job(-0.75));
        RenderFrame calculatedFrame = RenderFrame.create(job(-0.5));
        try (PersistentFrameRenderBackend backend =
                     new PersistentFrameRenderBackend(new DirectDoubleRenderBackend(), store())) {
            backend.render(restoredFrame, () -> false, ignored -> {}, null);
            assertTrue(backend.takeRestoredRefinement(source).isEmpty(), "Another frame object gets nothing");
            RetainedRefinement restored = backend.takeRestoredRefinement(restoredFrame).orElseThrow();
            assertSameRefinement(refinement(source, CUSTOM), restored);
            assertTrue(backend.takeRestoredRefinement(restoredFrame).isEmpty(), "Handed out only once");

            backend.render(RenderFrame.create(job(-0.75)), () -> false, ignored -> {}, null);
            backend.render(calculatedFrame, () -> false, ignored -> {}, null);
            assertTrue(backend.takeRestoredRefinement(calculatedFrame).isEmpty(), "A calculated frame has none");
        }
    }

    @Test
    void cancellationWhileReadingTheAntialiasingKeepsTheEntry() throws Exception {
        calculateAndStore(job(-0.75), CUSTOM);
        RenderFrame frame = RenderFrame.create(job(-0.75));
        try (PersistentFrameStore store = store()) {
            assertTrue(store.load(frame, () -> false));
            assertNull(store.loadRefinement(frame, () -> true));
        }
        assertTrue(Files.isRegularFile(refinementFile(job(-0.75))), "Cancellation must not discard the entry");
        assertNotNull(restore(job(-0.75)));
    }

    @Test
    void aRenderCancelledAfterItsBaseFrameKeepsNoAntialiasing() throws Exception {
        calculateAndStore(job(-0.75), CUSTOM);
        RenderFrame frame = RenderFrame.create(job(-0.75));
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        try (PersistentFrameRenderBackend backend =
                     new PersistentFrameRenderBackend(new DirectDoubleRenderBackend(), store())) {
            // Cancelled once the restored base frame is reported, before its AA is read.
            backend.render(frame, cancelled::get, ignored -> cancelled.set(true), null);
            assertTrue(frame.isComplete());
            assertTrue(backend.takeRestoredRefinement(frame).isEmpty());
        }
        assertTrue(Files.isRegularFile(refinementFile(job(-0.75))));
    }

    @Test
    void antialiasingIsStoredOnlyBesideAStoredFrame() throws Exception {
        RenderFrame frame = RenderFrame.create(job(-0.75));
        new DirectDoubleRenderBackend().render(frame, () -> false, ignored -> {}, null);
        try (PersistentFrameStore store = store()) {
            store.saveRefinementWhenSettled(frame, refinement(frame, CUSTOM));
        }
        assertFalse(Files.exists(refinementFile(job(-0.75))));
    }

    @Test
    void discardsCorruptedTruncatedAndOutdatedAntialiasingButKeepsTheFrame() throws Exception {
        RenderJob job = job(-0.75);
        calculateAndStore(job, CUSTOM);
        Path file = refinementFile(job);
        byte[] valid = Files.readAllBytes(file);

        byte[] corrupted = valid.clone();
        corrupted[corrupted.length - 3] ^= 0x10;
        Files.write(file, corrupted);
        assertNull(restore(job), "A payload with a bad checksum must be rejected");
        assertFalse(Files.exists(file), "A rejected entry is deleted");

        Files.write(file, java.util.Arrays.copyOf(valid, valid.length - 1));
        assertNull(restore(job));
        assertFalse(Files.exists(file));

        byte[] outdated = valid.clone();
        // Magic, format version and calculation version precede the AA version.
        ByteBuffer.wrap(outdated).putInt(8 + 2 * Integer.BYTES, PersistentFrameStore.ANTIALIAS_VERSION + 1);
        Files.write(file, outdated);
        assertNull(restore(job));
        assertFalse(Files.exists(file));

        Files.write(file, valid);
        assertNotNull(restore(job), "The intact entry still restores");
    }

    @Test
    void invalidSampleValuesAreRejectedEvenWithAMatchingChecksum() throws Exception {
        RenderJob job = job(-0.75);
        calculateAndStore(job, CUSTOM);
        Path file = refinementFile(job);
        byte[] valid = Files.readAllBytes(file);
        // The first sample entry starts after the colors, the ready flags and the sample header.
        int size = job.width() * job.height();
        long payloadStart = valid.length - (3L * 12 + 17 + size * 5L)
                - (17 + 40 * 14L + refinement(RenderFrame.create(job), CUSTOM).samples().phaseCount() * 2L);
        int firstEntry = (int) (payloadStart + 3L * 12 + 17 + size * 5L + 17);
        byte[] badPixel = valid.clone();
        ByteBuffer.wrap(badPixel).putInt(firstEntry, size);
        rewriteChecksum(badPixel, (int) payloadStart);
        Files.write(file, badPixel);
        assertNull(restore(job), "A sample outside the frame must be rejected");
        assertFalse(Files.exists(file));
    }

    /** Recomputes the payload CRC-32, which the header ends with. */
    private static void rewriteChecksum(byte[] file, int payloadStart) {
        var crc = new java.util.zip.CRC32();
        crc.update(file, payloadStart, file.length - payloadStart);
        ByteBuffer.wrap(file).putLong(payloadStart - Long.BYTES, crc.getValue());
    }

    @Test
    void unchangedAntialiasingIsNotRewrittenButChangedAntialiasingIs() throws Exception {
        RenderJob job = job(-0.75);
        calculateAndStore(job, CUSTOM);
        Path file = refinementFile(job);
        Object original = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        assumeTrue(original != null, "The file system must identify files to detect a rewrite");

        RenderFrame frame = RenderFrame.create(job);
        try (PersistentFrameStore store = store()) {
            assertTrue(store.load(frame, () -> false));
            store.saveRefinementWhenSettled(frame, store.loadRefinement(frame, () -> false));
        }
        assertEquals(original, Files.readAttributes(file, BasicFileAttributes.class).fileKey(),
                "Storing the restored AA again must not rewrite it");
        // A finished pass retains equal AA in new objects, and a later launch remembers nothing.
        try (PersistentFrameStore store = store()) {
            store.saveRefinementWhenSettled(frame, refinement(frame, CUSTOM));
        }
        assertEquals(original, Files.readAttributes(file, BasicFileAttributes.class).fileKey(),
                "Equal AA must not be rewritten, whatever objects hold it");

        var fire = new ColoringSettings(PalettePreset.FIRE, 1.7, 0.3);
        try (PersistentFrameStore store = store()) {
            store.saveRefinementWhenSettled(frame, refinement(frame, fire));
        }
        assertEquals(fire, restore(job).coloring(), "AA of another coloring replaces the stored AA");
    }

    @Test
    void aDamagedPayloadBehindAnIntactHeaderIsReplacedByEqualAntialiasing() throws Exception {
        RenderJob job = job(-0.75);
        RenderFrame frame = calculateAndStore(job, CUSTOM);
        Path file = refinementFile(job);
        byte[] damaged = Files.readAllBytes(file);
        damaged[damaged.length - 1] ^= 0x01;
        Files.write(file, damaged);

        try (PersistentFrameStore store = store()) {
            store.saveRefinementWhenSettled(frame, refinement(frame, CUSTOM));
        }
        RetainedRefinement restored = restore(job);
        assertNotNull(restored, "The damaged file must be replaced, not kept");
        assertSameRefinement(refinement(frame, CUSTOM), restored);
    }

    @Test
    void evictingAFrameDeletesItsAntialiasing() throws Exception {
        RenderJob first = job(-0.75);
        calculateAndStore(first, CUSTOM);
        assertTrue(Files.exists(refinementFile(first)));
        long frameBytes = Files.size(frameFile(first));
        // Room for one base frame only: storing the next one evicts the first with its AA.
        calculateAndStore(job(-0.5), null, frameBytes + 512);
        assertFalse(Files.exists(frameFile(first)));
        assertFalse(Files.exists(refinementFile(first)), "AA without its base frame is useless");
        assertTrue(Files.exists(frameFile(job(-0.5))));
    }

    @Test
    void storingAntialiasingKeepsItsFrameWhenOlderEntriesAreEvicted() throws Exception {
        RenderJob first = job(-0.75);
        RenderJob second = job(-0.5);
        calculateAndStore(first, null);
        long frameBytes = Files.size(frameFile(first));
        RenderFrame secondFrame = calculateAndStore(second, null);
        // The AA of the second frame needs the room of the first frame, never of its own.
        long budget = 2 * frameBytes + 512;
        try (PersistentFrameStore store = store(budget)) {
            store.saveRefinementWhenSettled(secondFrame, refinement(secondFrame, CUSTOM));
        }
        assertTrue(Files.exists(frameFile(second)));
        assertTrue(Files.exists(refinementFile(second)));
        assertFalse(Files.exists(frameFile(first)));
    }

    @Test
    void antialiasingThatDoesNotFitBesideItsFrameIsNotStoredAndTheFrameIsKept() throws Exception {
        RenderJob job = job(-0.75);
        RenderFrame frame = calculateAndStore(job, null);
        // Room for the base frame alone: storing its AA must not evict the frame with it.
        try (PersistentFrameStore store = store(Files.size(frameFile(job)) + 512)) {
            store.saveRefinementWhenSettled(frame, refinement(frame, CUSTOM));
        }
        assertTrue(Files.exists(frameFile(job)), "The base frame must survive");
        assertFalse(Files.exists(refinementFile(job)));
    }

    @Test
    void aFrameAndItsAntialiasingAreEvictedAsOneEntry() throws Exception {
        RenderJob used = job(-0.75);
        RenderJob older = job(-0.6);
        RenderJob oldest = job(-0.55);
        calculateAndStore(used, CUSTOM);
        calculateAndStore(older, null);
        calculateAndStore(oldest, null);
        long frameBytes = Files.size(frameFile(used));
        long refinementBytes = Files.size(refinementFile(used));
        // The AA of the oldest frame was used last, so that frame is the most recent entry.
        Instant now = Instant.now();
        Files.setLastModifiedTime(frameFile(used), FileTime.from(now.minusSeconds(40)));
        Files.setLastModifiedTime(frameFile(older), FileTime.from(now.minusSeconds(30)));
        Files.setLastModifiedTime(frameFile(oldest), FileTime.from(now.minusSeconds(50)));
        Files.setLastModifiedTime(refinementFile(used), FileTime.from(now.minusSeconds(20)));
        // The new frame and the used entry fit; nothing else does.
        long budget = 3 * frameBytes + refinementBytes / 2;
        calculateAndStore(job(-0.5), null, budget);

        assertTrue(Files.exists(frameFile(job(-0.5))));
        assertTrue(Files.exists(frameFile(used)), "The entry is as recent as its newer file");
        assertTrue(Files.exists(refinementFile(used)));
        assertFalse(Files.exists(frameFile(older)));
        assertFalse(Files.exists(frameFile(oldest)));
        long stored;
        try (var files = Files.list(directory)) {
            stored = files.mapToLong(file -> file.toFile().length()).sum();
        }
        assertTrue(stored <= budget, "The store must fit its budget");
    }

    @Test
    void samplesOverTheMemoryLimitAreRejectedAndTheFrameIsKept() throws Exception {
        RenderJob job = job(-0.75);
        RenderFrame source = calculateAndStore(job, CUSTOM);
        var samples = refinement(source, CUSTOM).samples();
        long sampleBytes = AntialiasSampleCache.retainedBytesFor(samples.size(), samples.phaseCount());
        RenderFrame frame = RenderFrame.create(job);
        try (PersistentFrameStore store = new PersistentFrameStore(directory, PersistentFrameStore.DEFAULT_MAX_BYTES,
                Duration.ZERO, sampleBytes - 1)) {
            assertTrue(store.load(frame, () -> false));
            assertNull(store.loadRefinement(frame, () -> false),
                    "Samples beyond the memory limit must be rejected before they are read");
        }
        assertFalse(Files.exists(refinementFile(job)));
        assertTrue(Files.exists(frameFile(job)));
    }

    @Test
    void anInvalidFrameDeletesItsAntialiasing() throws Exception {
        RenderJob job = job(-0.75);
        calculateAndStore(job, CUSTOM);
        Path frame = frameFile(job);
        try (FileChannel channel = FileChannel.open(frame, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 1);
        }
        try (PersistentFrameStore store = store()) {
            assertFalse(store.load(RenderFrame.create(job), () -> false));
        }
        assertFalse(Files.exists(refinementFile(job)));
    }

    @Test
    void colorsOnlyAndSamplesOnlyAntialiasingRoundTrip() throws Exception {
        RenderJob job = job(-0.75);
        RenderFrame frame = calculateAndStore(job, null);
        RetainedRefinement full = refinement(frame, CUSTOM);
        try (PersistentFrameStore store = store()) {
            store.saveRefinementWhenSettled(frame, full.withoutSamples());
        }
        RetainedRefinement colors = restore(job);
        assertNull(colors.samples());
        assertTrue(full.pixels().sameRefinedPixels(colors.pixels()));
        assertEquals(CUSTOM, colors.coloring());

        try (PersistentFrameStore store = store()) {
            store.saveRefinementWhenSettled(frame, new RetainedRefinement(null, null, full.samples()));
        }
        RetainedRefinement samples = restore(job);
        assertNull(samples.pixels());
        assertNull(samples.coloring());
        assertEquals(full.samples().size(), samples.samples().size());
    }

    @Test
    void closeWritesThePendingFrameBeforeItsAntialiasing() throws Exception {
        RenderJob job = job(-0.75);
        RenderFrame frame = RenderFrame.create(job);
        // A long settle delay leaves both writes pending until close.
        try (PersistentFrameRenderBackend backend = new PersistentFrameRenderBackend(new DirectDoubleRenderBackend(),
                new PersistentFrameStore(directory, PersistentFrameStore.DEFAULT_MAX_BYTES, Duration.ofHours(1)))) {
            backend.render(frame, () -> false, ignored -> {}, null);
            backend.saveRefinementWhenSettled(frame, refinement(frame, CUSTOM));
            assertFalse(Files.exists(frameFile(job)));
        }
        assertNotNull(restore(job));
    }

    @Test
    void refinementFileNamesFollowTheirFrame() {
        Path frame = directory.resolve("abc" + PersistentFrameStore.SUFFIX);
        assertEquals(directory.resolve("abc" + PersistentFrameStore.REFINEMENT_SUFFIX),
                PersistentFrameStore.refinementFileFor(frame));
    }
}
