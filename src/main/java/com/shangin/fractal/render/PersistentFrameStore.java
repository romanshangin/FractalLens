package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.scene.ColoringSettings;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

/**
 * Disk store of completed base frames, so a view calculated in an earlier launch is
 * shown again without recalculating its samples.
 *
 * <p>One file holds one frame's numerical samples (iterations, smooth iterations, escape
 * flags and, when the formula stores them, orbit-trap distances) under an exact key:
 * formula and parameters, orbit trap, sample accuracy, the decimal viewport with its
 * render grid derived from it, render size and iteration limit. The file also records the render
 * grid it was calculated on; a frame whose grid differs is never filled. Palette and
 * display settings are not part of the key, because colors are derived from the samples.
 *
 * <p>A second file beside it holds the frame's finished antialiasing, as kept in memory by
 * {@link RetainedRefinement}: the AA display colors with the coloring that produced them,
 * and the palette-independent AA samples. It is only read after its base frame was
 * restored, is versioned by {@link #ANTIALIAS_VERSION} and is deleted with its base frame.
 * Rewriting it with AA colors alone keeps its stored samples, which are exact for the frame.
 *
 * <p>Loading runs on the calling render thread and is cancellable between chunks. Saving
 * runs on a background writer once a completed frame has remained the most recently
 * completed one for a short settle delay, so a gesture that completes many frames writes
 * only where it stops. Files are written to a temporary name and published by an atomic
 * move; a CRC-32 of the payload, sizes and versions are validated before any sample is
 * marked ready. Corrupt, truncated or outdated entries are deleted and the frame is
 * calculated normally. A shared byte budget evicts least recently used entries.
 *
 * <p>Completed frames are not modified afterwards (backends calculate only missing pixels
 * into new frames), so the writer reads a frame's arrays without copying them.
 */
public final class PersistentFrameStore implements AutoCloseable {

    /** Default disk budget; provisional until retained sizes are measured. */
    public static final long DEFAULT_MAX_BYTES = 1024L * 1024L * 1024L;
    static final Duration DEFAULT_SETTLE_DELAY = Duration.ofSeconds(1);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration STALE_TEMPORARY_AGE = Duration.ofMinutes(10);

    static final int FORMAT_VERSION = 1;
    /**
     * Version of the calculated sample semantics. Increment it whenever a formula,
     * backend selection, precision policy or sample encoding change alters the samples
     * that the same key produces, so older entries are discarded instead of shown.
     * Version 2: deep Julia recovery references became coordinate-determined.
     * Version 3: deep Mandelbrot base recovery references became coordinate-determined, and
     * scalar and scaled perturbation stopped trusting escape decisions within their rounding
     * bounds.
     * Version 4: deep Mandelbrot reference orbits take the exact sign of their escape margin and
     * continue briefly past their escape, BLA tracks the error bound and skips no escape test
     * near the radius, decisions near the radius use the exact reference margin, and the
     * bound stays tracked up to 1e-6.
     */
    static final int CALCULATION_VERSION = 4;

    private static final byte[] MAGIC = "FLFRAME\0".getBytes(StandardCharsets.US_ASCII);
    static final String SUFFIX = ".frame";

    /** Layout of the antialiasing file stored beside a base frame. */
    static final int REFINEMENT_FORMAT_VERSION = 2;
    /**
     * Version of the antialiasing semantics: subpixel sampling grids, candidate edge
     * thresholds, phase encoding and color averaging. Increment it whenever a change alters
     * the AA colors or samples stored for an unchanged frame and coloring.
     * Version 2: deep AA recovery references became coordinate-determined.
     * Version 3: deep Mandelbrot AA recovery stopped trusting escape decisions within its
     * rounding bound, and offsets that lose pixel resolution as doubles use scaled deltas.
     * Version 4: deep Mandelbrot AA samples follow the escape decisions of calculation
     * version 4, and recovery references settle samples only within their error bound.
     * Version 5: histogram AA colors no longer keep pixels shifted from another frame,
     * which earlier versions stored with that frame's mapping.
     */
    static final int ANTIALIAS_VERSION = 5;
    private static final byte[] REFINEMENT_MAGIC = "FLAAREF\0".getBytes(StandardCharsets.US_ASCII);
    static final String REFINEMENT_SUFFIX = ".aa";
    private static final int MAX_PALETTE_STOPS = 4_096;
    private static final int MAX_AA_SAMPLES = Integer.SIZE;
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final int MAX_TEXT_BYTES = 1 << 20;
    private static final int CHUNK_BYTES = 1 << 20;
    private static final System.Logger LOGGER = System.getLogger(PersistentFrameStore.class.getName());

    private final Path directory;
    private final long maxBytes;
    /**
     * Memory limit of restored AA samples: the interactive sample cache's own budget, so
     * nothing larger was ever retained, and a corrupt header cannot exhaust the heap.
     */
    private final long maxSampleBytes;
    private final long settleDelayNanos;
    private final ScheduledThreadPoolExecutor writer;
    private final AtomicLong temporaryCounter = new AtomicLong();
    private final AtomicBoolean writeFailureLogged = new AtomicBoolean();
    private final Object lock = new Object();
    private PendingSave pending;
    private PendingSave pendingRefinement;
    private boolean closed;

    private static final class PendingSave {
        private final RenderFrame frame;
        private final RetainedRefinement refinement;
        private ScheduledFuture<?> future;

        private PendingSave(RenderFrame frame, RetainedRefinement refinement) {
            this.frame = frame;
            this.refinement = refinement;
        }
    }

    public PersistentFrameStore(Path directory) {
        this(directory, DEFAULT_MAX_BYTES, DEFAULT_SETTLE_DELAY);
    }

    PersistentFrameStore(Path directory, long maxBytes, Duration settleDelay) {
        this(directory, maxBytes, settleDelay, AntialiasSampleCache.DEFAULT_MAX_BYTES);
    }

    PersistentFrameStore(Path directory, long maxBytes, Duration settleDelay, long maxSampleBytes) {
        if (maxSampleBytes < 0) {
            throw new IllegalArgumentException("Sample memory limit must not be negative");
        }
        this.maxSampleBytes = maxSampleBytes;
        this.directory = Objects.requireNonNull(directory).toAbsolutePath();
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Frame store budget must be positive");
        }
        if (settleDelay.isNegative()) {
            throw new IllegalArgumentException("Settle delay must not be negative");
        }
        this.maxBytes = maxBytes;
        this.settleDelayNanos = settleDelay.toNanos();
        writer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "fractal-frame-store");
            thread.setDaemon(true);
            return thread;
        });
        writer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        writer.setRemoveOnCancelPolicy(true);
        writer.execute(this::deleteStaleTemporaries);
    }

    /** The directory holding this store's files. */
    public Path directory() {
        return directory;
    }

    /** Whether frames of this kind can be stored and restored. */
    static boolean isPersistable(RenderFrame frame) {
        return frame.request().formula().preset() != null
                && frame.samplePlane() instanceof FractalData
                && frame.renderGrid() != null;
    }

    /**
     * Fills an empty frame from a stored entry and marks every pixel ready.
     *
     * @return true when the frame is now complete; false on a miss, a rejected entry,
     *         an I/O failure or cancellation, leaving every pixel not ready
     */
    public boolean load(RenderFrame frame, BooleanSupplier cancelled) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(cancelled);
        if (!isPersistable(frame) || frame.validity().readyPixelCount() != 0) {
            return false;
        }
        String key = keyText(frame.request());
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        FractalData samples = frame.fractalData();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            Header header = readHeader(channel);
            if (!header.key().equals(key)) {
                // A digest collision with another view: not an error, only a miss.
                return false;
            }
            boolean storesOrbitTraps = samples.orbitTrapDistancesArray() != null;
            if (!header.grid().equals(gridText(frame.renderGrid()))
                    || header.width() != samples.width()
                    || header.height() != samples.height()
                    || header.maxIterations() != samples.maxIterations()
                    || header.orbitTrapDistances() != storesOrbitTraps) {
                throw new InvalidEntryException("entry does not match its key");
            }
            long payloadBytes = payloadBytes(samples.size(), storesOrbitTraps);
            if (header.payloadBytes() != payloadBytes
                    || channel.size() != channel.position() + payloadBytes) {
                throw new InvalidEntryException("unexpected entry length");
            }
            CRC32 crc = new CRC32();
            if (!readPayload(channel, samples, crc, cancelled)) {
                return false;
            }
            if (crc.getValue() != header.crc()) {
                throw new InvalidEntryException("payload checksum mismatch");
            }
        } catch (InvalidEntryException exception) {
            LOGGER.log(System.Logger.Level.INFO, "Discarding a stored frame: " + exception.getMessage());
            deleteQuietly(file);
            deleteQuietly(refinementFileFor(file));
            return false;
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (NoSuchFileException exception) {
            return false;
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not read a stored frame: " + exception.getMessage());
            return false;
        }
        touch(file);
        frame.validity().markReady(new RenderRegion(0, 0, samples.width(), samples.height()));
        return true;
    }

    /**
     * Schedules a completed frame to be written once no other frame completes within
     * the settle delay. Replaces an earlier frame that has not been written yet.
     */
    public void saveWhenSettled(RenderFrame frame) {
        Objects.requireNonNull(frame);
        if (!isPersistable(frame) || !frame.isComplete()) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (pending != null) {
                pending.future.cancel(false);
            }
            PendingSave next = new PendingSave(frame, null);
            pending = next;
            next.future = writer.schedule(() -> writeIfStillPending(next), settleDelayNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void writeIfStillPending(PendingSave save) {
        synchronized (lock) {
            if (pending != save) {
                return;
            }
            pending = null;
        }
        write(save.frame);
    }

    /**
     * Schedules a completed frame's finished antialiasing to be written beside its stored
     * base frame once no other AA is retained within the settle delay. It is written only
     * when the base frame is stored by then; AA identical to the stored AA is not rewritten,
     * and AA colors without samples keep the samples already stored for the frame.
     */
    public void saveRefinementWhenSettled(RenderFrame frame, RetainedRefinement refinement) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(refinement);
        if (!isPersistable(frame) || !frame.isComplete()) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (pendingRefinement != null) {
                pendingRefinement.future.cancel(false);
            }
            PendingSave next = new PendingSave(frame, refinement);
            pendingRefinement = next;
            next.future = writer.schedule(() -> writeRefinementIfStillPending(next),
                    settleDelayNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void writeRefinementIfStillPending(PendingSave save) {
        synchronized (lock) {
            if (pendingRefinement != save) {
                return;
            }
            pendingRefinement = null;
        }
        writeRefinement(save.frame, save.refinement);
    }

    /**
     * Writes the frame still waiting for its settle delay, then stops the writer. Waits
     * a bounded time; an interrupted write leaves only a temporary file, which is never
     * read and is removed by a later store. Idempotent.
     */
    @Override
    public void close() {
        PendingSave flush;
        PendingSave flushRefinement;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            flush = pending;
            pending = null;
            flushRefinement = pendingRefinement;
            pendingRefinement = null;
        }
        // The base frame first: its antialiasing is written only beside a stored frame.
        if (flush != null) {
            flush.future.cancel(false);
            writer.execute(() -> write(flush.frame));
        }
        if (flushRefinement != null) {
            flushRefinement.future.cancel(false);
            writer.execute(() -> writeRefinement(flushRefinement.frame, flushRefinement.refinement));
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.log(System.Logger.Level.WARNING, "Stopping an unfinished frame store write");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void write(RenderFrame frame) {
        RenderJob job = frame.request();
        String grid = gridText(frame.renderGrid());
        // Only an empty frame on the viewport's fresh grid is ever restored. A frame on a
        // grid shifted from an earlier frame (pan reuse) keeps that grid's exact sample
        // positions, which differ from the fresh grid, so storing it could never hit.
        if (!grid.equals(gridText(RenderGrid.from(job.viewport(), job.width(), job.height())))) {
            return;
        }
        String key = keyText(job);
        Path target = fileFor(key);
        FractalData samples = frame.fractalData();
        boolean storesOrbitTraps = samples.orbitTrapDistancesArray() != null;
        long payloadBytes = payloadBytes(samples.size(), storesOrbitTraps);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] gridBytes = grid.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = headerBuffer(keyBytes, gridBytes, samples, storesOrbitTraps, payloadBytes);
        if (header.remaining() + payloadBytes > maxBytes) {
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isRegularFile(target)) {
                // Same key and calculation version: the stored samples are identical.
                touch(target);
                return;
            }
            temporary = directory.resolve(target.getFileName() + "." + ProcessHandle.current().pid()
                    + "." + temporaryCounter.incrementAndGet() + TEMPORARY_SUFFIX);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                long crcOffset = header.limit() - Long.BYTES;
                writeFully(channel, header);
                CRC32 crc = new CRC32();
                writePayload(channel, samples, crc);
                writeFully(channel, ByteBuffer.allocate(Long.BYTES).putLong(0, crc.getValue()), crcOffset);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            evict(Set.of(target));
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            logWriteFailure(exception);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    private void logWriteFailure(Exception exception) {
        if (writeFailureLogged.compareAndSet(false, true)) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not store a completed frame; rendering continues without it: "
                            + exception.getMessage());
        }
    }

    /**
     * Reads the antialiasing stored beside a frame that {@link #load} has just restored.
     * Every length, count and value is validated and the payload checksum must match
     * before anything is returned.
     *
     * @return the stored AA, or null on a miss, a rejected entry, an I/O failure or
     *         cancellation; a rejected entry is deleted
     */
    public RetainedRefinement loadRefinement(RenderFrame frame, BooleanSupplier cancelled) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(cancelled);
        try {
            return readRefinement(frame, cancelled);
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not read stored antialiasing: " + exception.getMessage());
            return null;
        }
    }

    /**
     * {@link #loadRefinement} that reports I/O failures, so a caller can tell an unreadable
     * entry from a missing, rejected or cancelled one, which all give null.
     */
    private RetainedRefinement readRefinement(RenderFrame frame, BooleanSupplier cancelled) throws IOException {
        if (!isPersistable(frame) || !frame.isComplete()) {
            return null;
        }
        String key = keyText(frame.request());
        Path file = refinementFileFor(fileFor(key));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        RetainedRefinement refinement;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            RefinementHeader header = readRefinementHeader(channel);
            if (!header.key().equals(key)) {
                return null;
            }
            int width = frame.samplePlane().width();
            int height = frame.samplePlane().height();
            int size = frame.samplePlane().size();
            if (!header.grid().equals(gridText(frame.renderGrid()))
                    || header.width() != width || header.height() != height) {
                throw new InvalidEntryException("antialiasing does not match its frame");
            }
            boolean colors = header.stopCount() > 0;
            boolean samples = header.samples();
            if (!colors && !samples
                    || !samples && (header.entryCount() != 0 || header.phaseCount() != 0)
                    || colors && (header.stopCount() < 2 || header.stopCount() > MAX_PALETTE_STOPS)
                    || colors == header.palette().isEmpty() || colors == header.orbitTrap().isEmpty()
                    || header.entryCount() < 0 || header.entryCount() > size
                    || header.phaseCount() < header.entryCount()
                    || header.phaseCount() > (long) header.entryCount() * MAX_AA_SAMPLES) {
                throw new InvalidEntryException("invalid antialiasing header");
            }
            if (samples && AntialiasSampleCache.retainedBytesFor(header.entryCount(), header.phaseCount())
                    > maxSampleBytes) {
                throw new InvalidEntryException("antialiasing samples exceed the memory limit");
            }
            long payloadBytes = refinementPayloadBytes(size, header.stopCount(), samples, header.entryCount(),
                    header.phaseCount());
            if (header.payloadBytes() != payloadBytes
                    || channel.size() != channel.position() + payloadBytes) {
                throw new InvalidEntryException("unexpected antialiasing length");
            }
            // The whole payload is checked before any array sized by the header is allocated.
            long payloadStart = channel.position();
            new PayloadReader(channel, payloadBytes, cancelled).verify(header.crc());
            channel.position(payloadStart);
            PayloadReader in = new PayloadReader(channel, payloadBytes, cancelled);
            ColoringSettings coloring = null;
            RefinedPixelSnapshot pixels = null;
            if (colors) {
                coloring = readColoring(in, header);
                pixels = readRefinedPixels(in, width, height);
            }
            AntialiasSampleCache.Retained retained = samples
                    ? readSamples(in, size, header.entryCount(), header.phaseCount()) : null;
            in.finish(header.crc());
            refinement = new RetainedRefinement(pixels, coloring, retained);
        } catch (LoadCancelledException exception) {
            return null;
        } catch (InvalidEntryException exception) {
            LOGGER.log(System.Logger.Level.INFO, "Discarding stored antialiasing: " + exception.getMessage());
            deleteQuietly(file);
            return null;
        } catch (IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.INFO, "Discarding stored antialiasing: " + exception.getMessage());
            deleteQuietly(file);
            return null;
        } catch (NoSuchFileException exception) {
            return null;
        }
        touch(file);
        return refinement;
    }

    /**
     * The AA samples stored for a completed frame that was not just restored, such as a
     * cached frame shown again after the in-memory frame cache dropped its samples. Only a
     * frame on its viewport's fresh grid can match the stored AA; any other frame misses
     * without touching the entry.
     *
     * @return the stored samples, or null on a miss, an entry without samples, a rejected
     *         entry, an I/O failure or cancellation
     */
    public AntialiasSampleCache.Retained loadStoredSamples(RenderFrame frame, BooleanSupplier cancelled) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(cancelled);
        RenderJob job = frame.request();
        if (!isPersistable(frame) || !frame.isComplete()
                || !gridText(frame.renderGrid()).equals(gridText(RenderGrid.from(job.viewport(), job.width(), job.height())))) {
            return null;
        }
        RetainedRefinement stored = loadRefinement(frame, cancelled);
        return stored == null ? null : stored.samples();
    }

    private void writeRefinement(RenderFrame frame, RetainedRefinement refinement) {
        RenderJob job = frame.request();
        String grid = gridText(frame.renderGrid());
        // Like base frames, only AA on the viewport's fresh grid can ever be restored.
        if (!grid.equals(gridText(RenderGrid.from(job.viewport(), job.width(), job.height())))) {
            return;
        }
        RefinedPixelSnapshot pixels = refinement.pixels();
        if (pixels != null && (pixels.width() != job.width() || pixels.height() != job.height())) {
            return;
        }
        String key = keyText(job);
        Path frameFile = fileFor(key);
        Path target = refinementFileFor(frameFile);
        Path temporary = null;
        try {
            if (!Files.isRegularFile(frameFile)) {
                // AA is only ever read after its base frame, so without one it is never stored.
                return;
            }
            refinement = keepingStoredSamples(frame, refinement, target);
            if (refinement == null) {
                return;
            }
            ColoringSettings coloring = refinement.coloring();
            AntialiasSampleCache.Retained samples = refinement.samples();
            int size = Math.multiplyExact(job.width(), job.height());
            int stopCount = pixels == null ? 0 : coloring.paletteStops().size();
            int entryCount = samples == null ? 0 : samples.size();
            int phaseCount = samples == null ? 0 : samples.phaseCount();
            if (stopCount == 0 && samples == null || stopCount > MAX_PALETTE_STOPS) {
                return;
            }
            long payloadBytes = refinementPayloadBytes(size, stopCount, samples != null, entryCount, phaseCount);
            ByteBuffer header = refinementHeaderBuffer(key, grid, job.width(), job.height(),
                    pixels == null ? null : coloring, stopCount, samples != null, entryCount, phaseCount,
                    payloadBytes);
            // Eviction would delete AA that does not fit with its frame, so it is not written.
            if (header.remaining() + payloadBytes + Files.size(frameFile) > maxBytes) {
                return;
            }
            if (Files.isRegularFile(target)) {
                // Every restored visit stores its AA again, mostly unchanged: compare the
                // stored file with the complete header, checksum included, without writing.
                PayloadWriter checksum = new PayloadWriter(null);
                writeRefinementPayload(checksum, pixels, coloring, samples, size);
                ByteBuffer expected = header.duplicate().putLong(header.limit() - Long.BYTES,
                        checksum.finish(payloadBytes));
                if (storedFileMatches(target, expected, payloadBytes, checksum.crc())) {
                    touch(target);
                    touch(frameFile);
                    return;
                }
            }
            temporary = directory.resolve(target.getFileName() + "." + ProcessHandle.current().pid()
                    + "." + temporaryCounter.incrementAndGet() + TEMPORARY_SUFFIX);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                long crcOffset = header.limit() - Long.BYTES;
                writeFully(channel, header);
                PayloadWriter out = new PayloadWriter(channel);
                writeRefinementPayload(out, pixels, coloring, samples, size);
                long crc = out.finish(payloadBytes);
                writeFully(channel, ByteBuffer.allocate(Long.BYTES).putLong(0, crc), crcOffset);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            // The base frame is in use with its AA, so it is as recent and kept with it.
            touch(frameFile);
            evict(Set.of(target, frameFile));
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            logWriteFailure(exception);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    /**
     * AA colors retained without samples keep the samples stored for the same frame. The
     * in-memory frame cache drops samples first when it runs short, for example while
     * another fractal is shown, so a later visit retains only colors; the samples on disk
     * are palette-independent and exact for this key and {@link #ANTIALIAS_VERSION}, so
     * they still serve recoloring after a restart. Without stored samples, or when they
     * were rejected as invalid, the refinement is returned unchanged; {@code null} when
     * reading them was interrupted, so the stored AA is kept as it is. An I/O failure is
     * thrown for the same reason.
     */
    private RetainedRefinement keepingStoredSamples(RenderFrame frame, RetainedRefinement refinement,
                                                    Path target) throws IOException {
        if (refinement.samples() != null || !Files.isRegularFile(target)) {
            return refinement;
        }
        // A read failure propagates, so the stored AA is kept rather than replaced without samples.
        RetainedRefinement stored = readRefinement(frame, () -> Thread.currentThread().isInterrupted());
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        return stored == null || stored.samples() == null
                ? refinement
                : new RetainedRefinement(refinement.pixels(), refinement.coloring(), stored.samples());
    }

    private static void writeRefinementPayload(PayloadWriter out, RefinedPixelSnapshot pixels,
                                               ColoringSettings coloring, AntialiasSampleCache.Retained samples,
                                               int size) throws IOException {
        if (pixels != null) {
            writeColoring(out, coloring);
            for (int index = 0; index < size; index++) out.putInt(pixels.colorAt(index));
            for (int index = 0; index < size; index++) out.putByte(pixels.isRefinedAt(index) ? 1 : 0);
        }
        if (samples != null) {
            out.putDouble(samples.colorScale());
            out.putDouble(samples.completeThreshold());
            out.putByte(samples.dropped() ? 1 : 0);
            samples.forEachEntry((pixel, mask, strength, deep, phases, from, length) -> {
                out.putInt(pixel);
                out.putInt(mask);
                out.putFloat(strength);
                out.putByte(deep ? 1 : 0);
                out.putByte(length);
                for (int offset = 0; offset < length; offset++) out.putShort(phases[from + offset]);
            });
        }
    }

    /**
     * True when {@code file} has the expected length, starts with {@code header}, whose key,
     * grid, coloring identity and counts then match, and its payload as read has the
     * checksum {@code crc}, so a damaged payload behind an intact header is replaced.
     */
    private static boolean storedFileMatches(Path file, ByteBuffer header, long payloadBytes, long crc) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() != header.remaining() + payloadBytes
                    || !readExactly(channel, header.remaining()).equals(header)) {
                return false;
            }
            new PayloadReader(channel, payloadBytes, () -> false).verify(crc);
            return true;
        } catch (IOException exception) {
            // Includes a payload that is truncated or fails the checksum.
            return false;
        }
    }

    private static long refinementPayloadBytes(int size, int stopCount, boolean samplesStored, int entryCount,
                                               long phaseCount) {
        long colors = stopCount == 0 ? 0L
                : Math.addExact(Math.multiplyExact((long) stopCount, Double.BYTES + Integer.BYTES)
                        + 2L * Double.BYTES + 1L,
                Math.multiplyExact((long) size, Integer.BYTES + 1L));
        long samples = !samplesStored ? 0L
                : Math.addExact(2L * Double.BYTES + 1L
                        + Math.multiplyExact((long) entryCount, 3L * Integer.BYTES + 2L),
                Math.multiplyExact(phaseCount, Short.BYTES));
        return Math.addExact(colors, samples);
    }

    private static ByteBuffer refinementHeaderBuffer(String key, String grid, int width, int height,
                                                     ColoringSettings coloring, int stopCount,
                                                     boolean samples, int entryCount, int phaseCount,
                                                     long payloadBytes) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] gridBytes = grid.getBytes(StandardCharsets.UTF_8);
        byte[] palette = (coloring == null ? "" : coloring.palette().name()).getBytes(StandardCharsets.UTF_8);
        byte[] orbitTrap = (coloring == null ? "" : coloring.orbitTrap().name()).getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = ByteBuffer.allocate(REFINEMENT_MAGIC.length + 3 * Integer.BYTES
                + 4 * Integer.BYTES + keyBytes.length + gridBytes.length + palette.length + orbitTrap.length
                + 6 * Integer.BYTES + 2 * Long.BYTES);
        header.put(REFINEMENT_MAGIC)
                .putInt(REFINEMENT_FORMAT_VERSION)
                .putInt(CALCULATION_VERSION)
                .putInt(ANTIALIAS_VERSION)
                .putInt(keyBytes.length).put(keyBytes)
                .putInt(gridBytes.length).put(gridBytes)
                .putInt(palette.length).put(palette)
                .putInt(orbitTrap.length).put(orbitTrap)
                .putInt(width)
                .putInt(height)
                .putInt(stopCount)
                .putInt(samples ? 1 : 0)
                .putInt(entryCount)
                .putInt(phaseCount)
                .putLong(payloadBytes)
                .putLong(0L);
        return header.flip();
    }

    private record RefinementHeader(String key, String grid, String palette, String orbitTrap, int width,
                                    int height, int stopCount, boolean samples, int entryCount, int phaseCount,
                                    long payloadBytes, long crc) {}

    private static RefinementHeader readRefinementHeader(FileChannel channel) throws IOException {
        ByteBuffer start = readExactly(channel, REFINEMENT_MAGIC.length + 3 * Integer.BYTES);
        byte[] magic = new byte[REFINEMENT_MAGIC.length];
        start.get(magic);
        if (!Arrays.equals(magic, REFINEMENT_MAGIC)) {
            throw new InvalidEntryException("not stored antialiasing");
        }
        if (start.getInt() != REFINEMENT_FORMAT_VERSION || start.getInt() != CALCULATION_VERSION
                || start.getInt() != ANTIALIAS_VERSION) {
            throw new InvalidEntryException("outdated format, calculation or antialiasing version");
        }
        String key = readText(channel);
        String grid = readText(channel);
        String palette = readText(channel);
        String orbitTrap = readText(channel);
        ByteBuffer rest = readExactly(channel, 6 * Integer.BYTES + 2 * Long.BYTES);
        int width = rest.getInt();
        int height = rest.getInt();
        int stopCount = rest.getInt();
        int samples = rest.getInt();
        if (samples != 0 && samples != 1) {
            throw new InvalidEntryException("invalid antialiasing header");
        }
        return new RefinementHeader(key, grid, palette, orbitTrap, width, height, stopCount, samples == 1,
                rest.getInt(), rest.getInt(), rest.getLong(), rest.getLong());
    }

    private static void writeColoring(PayloadWriter out, ColoringSettings coloring) throws IOException {
        for (ColorStop stop : coloring.paletteStops()) {
            out.putDouble(stop.position());
            out.putInt(stop.color());
        }
        out.putDouble(coloring.colorScale());
        out.putDouble(coloring.offset());
        out.putByte(coloring.histogramColoring() ? 1 : 0);
    }

    /** Throws {@link IllegalArgumentException} for values the coloring rejects. */
    private static ColoringSettings readColoring(PayloadReader in, RefinementHeader header) throws IOException {
        List<ColorStop> stops = new ArrayList<>(header.stopCount());
        for (int index = 0; index < header.stopCount(); index++) {
            double position = in.getDouble();
            stops.add(new ColorStop(position, in.getInt()));
        }
        double colorScale = in.getDouble();
        double offset = in.getDouble();
        boolean histogram = in.getFlag();
        return new ColoringSettings(PalettePreset.valueOf(header.palette()), stops, colorScale, offset,
                histogram, OrbitTrap.valueOf(header.orbitTrap()));
    }

    private static RefinedPixelSnapshot readRefinedPixels(PayloadReader in, int width, int height)
            throws IOException {
        int[] colors = new int[Math.multiplyExact(width, height)];
        for (int index = 0; index < colors.length; index++) colors[index] = in.getInt();
        ValidityMask validity = new ValidityMask(width, height);
        for (int y = 0; y < height; y++) {
            int runStart = -1;
            for (int x = 0; x <= width; x++) {
                boolean refined = x < width && in.getFlag();
                if (refined && runStart < 0) {
                    runStart = x;
                } else if (!refined && runStart >= 0) {
                    validity.markReady(new RenderRegion(runStart, y, x - runStart, 1));
                    runStart = -1;
                }
            }
        }
        return new RefinedPixelSnapshot(width, height, colors, validity);
    }

    private static AntialiasSampleCache.Retained readSamples(PayloadReader in, int size, int entryCount,
                                                             int phaseCount) throws IOException {
        double colorScale = in.getDouble();
        double completeThreshold = in.getDouble();
        boolean dropped = in.getFlag();
        if (!Double.isFinite(colorScale) || colorScale <= 0.0 || Double.isNaN(completeThreshold)) {
            throw new InvalidEntryException("invalid antialiasing samples");
        }
        int[] pixels = new int[entryCount];
        int[] masks = new int[entryCount];
        float[] strengths = new float[entryCount];
        byte[] deep = new byte[entryCount];
        int[] offsets = new int[entryCount + 1];
        short[] phases = new short[phaseCount];
        BitSet seen = new BitSet(size);
        int phase = 0;
        for (int entry = 0; entry < entryCount; entry++) {
            int pixel = in.getInt();
            int mask = in.getInt();
            float strength = in.getFloat();
            boolean deepZoom = in.getFlag();
            int length = Byte.toUnsignedInt(in.getByte());
            if (pixel < 0 || pixel >= size || seen.get(pixel) || Float.isNaN(strength)
                    || length < 1 || length > MAX_AA_SAMPLES || length > phaseCount - phase
                    || length < MAX_AA_SAMPLES && (mask >>> length) != 0) {
                throw new InvalidEntryException("invalid antialiasing entry");
            }
            seen.set(pixel);
            pixels[entry] = pixel;
            masks[entry] = mask;
            strengths[entry] = strength;
            deep[entry] = (byte) (deepZoom ? 1 : 0);
            for (int sample = 0; sample < length; sample++) {
                short value = in.getShort();
                // Samples that did not escape carry no phase.
                if ((mask & 1 << sample) == 0 && value != 0) {
                    throw new InvalidEntryException("invalid antialiasing phase");
                }
                phases[phase++] = value;
            }
            offsets[entry + 1] = phase;
        }
        if (phase != phaseCount) {
            throw new InvalidEntryException("unexpected antialiasing phase count");
        }
        return AntialiasSampleCache.restored(colorScale, completeThreshold, dropped,
                pixels, masks, strengths, deep, offsets, phases);
    }

    /**
     * Streams primitives through a chunk buffer, accumulating the payload checksum. Without
     * a channel it only computes the checksum.
     */
    private static final class PayloadWriter {
        private final FileChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK_BYTES);
        private final CRC32 crc = new CRC32();
        private long written;

        private PayloadWriter(FileChannel channel) {
            this.channel = channel;
        }

        private void reserve(int bytes) throws IOException {
            if (buffer.remaining() < bytes) flush();
        }

        void putByte(int value) throws IOException { reserve(1); buffer.put((byte) value); }
        void putShort(short value) throws IOException { reserve(Short.BYTES); buffer.putShort(value); }
        void putInt(int value) throws IOException { reserve(Integer.BYTES); buffer.putInt(value); }
        void putFloat(float value) throws IOException { reserve(Float.BYTES); buffer.putFloat(value); }
        void putDouble(double value) throws IOException { reserve(Double.BYTES); buffer.putDouble(value); }

        private void flush() throws IOException {
            buffer.flip();
            written += buffer.remaining();
            crc.update(buffer.duplicate());
            if (channel != null) writeFully(channel, buffer);
            buffer.clear();
        }

        /** The checksum of what {@link #finish} accepted. */
        long crc() {
            return crc.getValue();
        }

        /** Writes the rest and returns the checksum, after checking the written length. */
        long finish(long expectedBytes) throws IOException {
            flush();
            if (written != expectedBytes) {
                throw new IOException("Antialiasing payload length changed while writing");
            }
            return crc.getValue();
        }
    }

    /**
     * Reads primitives of a payload whose length was validated against the file, in
     * chunks, checking cancellation before each chunk and accumulating the checksum.
     */
    private static final class PayloadReader {
        private final FileChannel channel;
        private final BooleanSupplier cancelled;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK_BYTES);
        private final CRC32 crc = new CRC32();
        private long unread;

        private PayloadReader(FileChannel channel, long payloadBytes, BooleanSupplier cancelled) {
            this.channel = channel;
            this.unread = payloadBytes;
            this.cancelled = cancelled;
            buffer.limit(0);
        }

        private void require(int bytes) throws IOException {
            if (buffer.remaining() >= bytes) return;
            if (cancelled.getAsBoolean()) throw new LoadCancelledException();
            buffer.compact();
            int start = buffer.position();
            int length = (int) Math.min(buffer.remaining(), unread);
            buffer.limit(start + length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new InvalidEntryException("truncated antialiasing");
                }
            }
            unread -= length;
            crc.update(buffer.duplicate().flip().position(start));
            buffer.flip();
            if (buffer.remaining() < bytes) {
                throw new InvalidEntryException("truncated antialiasing");
            }
        }

        byte getByte() throws IOException { require(1); return buffer.get(); }
        short getShort() throws IOException { require(Short.BYTES); return buffer.getShort(); }
        int getInt() throws IOException { require(Integer.BYTES); return buffer.getInt(); }
        float getFloat() throws IOException { require(Float.BYTES); return buffer.getFloat(); }
        double getDouble() throws IOException { require(Double.BYTES); return buffer.getDouble(); }

        boolean getFlag() throws IOException {
            byte value = getByte();
            if (value != 0 && value != 1) {
                throw new InvalidEntryException("invalid antialiasing flag");
            }
            return value == 1;
        }

        /** Reads the whole payload, only to check its length and checksum. */
        void verify(long expectedCrc) throws IOException {
            while (unread > 0) {
                buffer.position(buffer.limit());
                require(1);
            }
            buffer.position(buffer.limit());
            finish(expectedCrc);
        }

        void finish(long expectedCrc) throws IOException {
            if (unread != 0 || buffer.hasRemaining()) {
                throw new InvalidEntryException("unexpected antialiasing length");
            }
            if (crc.getValue() != expectedCrc) {
                throw new InvalidEntryException("antialiasing checksum mismatch");
            }
        }
    }

    /** A load stopped by cancellation; the entry stays valid. */
    private static final class LoadCancelledException extends IOException {
        private LoadCancelledException() {
            super("cancelled");
        }
    }

    private static ByteBuffer headerBuffer(byte[] keyBytes, byte[] gridBytes, FractalData samples,
                                           boolean storesOrbitTraps, long payloadBytes) {
        ByteBuffer header = ByteBuffer.allocate(MAGIC.length + 4 * Integer.BYTES + keyBytes.length
                + gridBytes.length + 3 * Integer.BYTES + 1 + 2 * Long.BYTES);
        header.put(MAGIC)
                .putInt(FORMAT_VERSION)
                .putInt(CALCULATION_VERSION)
                .putInt(keyBytes.length).put(keyBytes)
                .putInt(gridBytes.length).put(gridBytes)
                .putInt(samples.width())
                .putInt(samples.height())
                .putInt(samples.maxIterations())
                .put((byte) (storesOrbitTraps ? 1 : 0))
                .putLong(payloadBytes)
                .putLong(0L);
        return header.flip();
    }

    private record Header(String key, String grid, int width, int height, int maxIterations,
                          boolean orbitTrapDistances, long payloadBytes, long crc) {}

    private static Header readHeader(FileChannel channel) throws IOException {
        ByteBuffer start = readExactly(channel, MAGIC.length + 2 * Integer.BYTES);
        byte[] magic = new byte[MAGIC.length];
        start.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new InvalidEntryException("not a stored frame");
        }
        if (start.getInt() != FORMAT_VERSION || start.getInt() != CALCULATION_VERSION) {
            throw new InvalidEntryException("outdated format or calculation version");
        }
        String key = readText(channel);
        String grid = readText(channel);
        ByteBuffer rest = readExactly(channel, 3 * Integer.BYTES + 1 + 2 * Long.BYTES);
        int width = rest.getInt();
        int height = rest.getInt();
        int maxIterations = rest.getInt();
        byte orbitTraps = rest.get();
        if (orbitTraps != 0 && orbitTraps != 1) {
            throw new InvalidEntryException("invalid header");
        }
        return new Header(key, grid, width, height, maxIterations, orbitTraps == 1, rest.getLong(), rest.getLong());
    }

    private static String readText(FileChannel channel) throws IOException {
        int length = readExactly(channel, Integer.BYTES).getInt();
        if (length < 0 || length > MAX_TEXT_BYTES) {
            throw new InvalidEntryException("invalid header length");
        }
        return StandardCharsets.UTF_8.decode(readExactly(channel, length)).toString();
    }

    private static ByteBuffer readExactly(FileChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new InvalidEntryException("truncated entry");
            }
        }
        return buffer.flip();
    }

    private static boolean readPayload(FileChannel channel, FractalData samples, CRC32 crc,
                                       BooleanSupplier cancelled) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK_BYTES);
        int size = samples.size();
        int[] iterations = samples.iterationsArray();
        for (int index = 0; index < size; ) {
            int count = Math.min(size - index, CHUNK_BYTES / Integer.BYTES);
            if (!readChunk(channel, buffer, count * Integer.BYTES, crc, cancelled)) return false;
            buffer.asIntBuffer().get(iterations, index, count);
            index += count;
        }
        if (!readDoubles(channel, buffer, samples.smoothIterationsArray(), crc, cancelled)) return false;
        boolean[] escaped = samples.escapedArray();
        for (int index = 0; index < size; ) {
            int count = Math.min(size - index, CHUNK_BYTES);
            if (!readChunk(channel, buffer, count, crc, cancelled)) return false;
            for (int offset = 0; offset < count; offset++) {
                byte value = buffer.get(offset);
                if (value != 0 && value != 1) {
                    throw new InvalidEntryException("invalid escape flag");
                }
                escaped[index + offset] = value == 1;
            }
            index += count;
        }
        double[] distances = samples.orbitTrapDistancesArray();
        return distances == null || readDoubles(channel, buffer, distances, crc, cancelled);
    }

    private static boolean readDoubles(FileChannel channel, ByteBuffer buffer, double[] values, CRC32 crc,
                                       BooleanSupplier cancelled) throws IOException {
        for (int index = 0; index < values.length; ) {
            int count = Math.min(values.length - index, CHUNK_BYTES / Double.BYTES);
            if (!readChunk(channel, buffer, count * Double.BYTES, crc, cancelled)) return false;
            buffer.asDoubleBuffer().get(values, index, count);
            index += count;
        }
        return true;
    }

    /** Reads exactly {@code length} bytes into the buffer start; false when cancelled. */
    private static boolean readChunk(FileChannel channel, ByteBuffer buffer, int length, CRC32 crc,
                                     BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            return false;
        }
        buffer.clear().limit(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new InvalidEntryException("truncated entry");
            }
        }
        buffer.flip();
        crc.update(buffer.duplicate());
        return true;
    }

    private static void writePayload(FileChannel channel, FractalData samples, CRC32 crc) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK_BYTES);
        int size = samples.size();
        int[] iterations = samples.iterationsArray();
        for (int index = 0; index < size; ) {
            int count = Math.min(size - index, CHUNK_BYTES / Integer.BYTES);
            buffer.clear();
            buffer.asIntBuffer().put(iterations, index, count);
            writeChunk(channel, buffer, count * Integer.BYTES, crc);
            index += count;
        }
        writeDoubles(channel, buffer, samples.smoothIterationsArray(), crc);
        boolean[] escaped = samples.escapedArray();
        for (int index = 0; index < size; ) {
            int count = Math.min(size - index, CHUNK_BYTES);
            buffer.clear();
            for (int offset = 0; offset < count; offset++) {
                buffer.put(offset, (byte) (escaped[index + offset] ? 1 : 0));
            }
            writeChunk(channel, buffer, count, crc);
            index += count;
        }
        double[] distances = samples.orbitTrapDistancesArray();
        if (distances != null) {
            writeDoubles(channel, buffer, distances, crc);
        }
    }

    private static void writeDoubles(FileChannel channel, ByteBuffer buffer, double[] values, CRC32 crc)
            throws IOException {
        for (int index = 0; index < values.length; ) {
            int count = Math.min(values.length - index, CHUNK_BYTES / Double.BYTES);
            buffer.clear();
            buffer.asDoubleBuffer().put(values, index, count);
            writeChunk(channel, buffer, count * Double.BYTES, crc);
            index += count;
        }
    }

    private static void writeChunk(FileChannel channel, ByteBuffer buffer, int length, CRC32 crc)
            throws IOException {
        buffer.position(0).limit(length);
        crc.update(buffer.duplicate());
        writeFully(channel, buffer);
    }

    /** Writes the buffer at the channel position and advances the position past it. */
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        channel.position(writeFully(channel, buffer, channel.position()));
    }

    /** Writes the buffer at {@code position}; returns the offset just past the written bytes. */
    private static long writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long offset = position;
        while (buffer.hasRemaining()) {
            offset += channel.write(buffer, offset);
        }
        return offset;
    }

    private static long payloadBytes(int size, boolean storesOrbitTraps) {
        long perPixel = Integer.BYTES + Double.BYTES + 1L + (storesOrbitTraps ? Double.BYTES : 0L);
        return Math.multiplyExact((long) size, perPixel);
    }

    /**
     * Deletes the least recently used entries until the store fits its budget. A base frame
     * and its antialiasing are one entry, as recent as its newer file, so neither is counted
     * after it is deleted. An entry that does not fit keeps its base frame without the
     * antialiasing when the frame alone fits. Antialiasing without its base frame is
     * useless, since it is only read after the frame, and is deleted.
     */
    private void evict(Set<Path> keep) throws IOException {
        final class Entry {
            Path frame;
            long frameBytes;
            Path refinement;
            long refinementBytes;
            FileTime modified;

            void add(FileTime time) {
                if (modified == null || time.compareTo(modified) > 0) modified = time;
            }

            boolean kept() {
                // Immutable sets reject null lookups.
                return frame != null && keep.contains(frame) || refinement != null && keep.contains(refinement);
            }
        }
        Map<Path, Entry> entries = new TreeMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory,
                "*{" + SUFFIX + "," + REFINEMENT_SUFFIX + "}")) {
            for (Path file : files) {
                try {
                    long bytes = Files.size(file);
                    FileTime modified = Files.getLastModifiedTime(file);
                    boolean refinement = file.getFileName().toString().endsWith(REFINEMENT_SUFFIX);
                    Path frame = refinement ? frameFileFor(file) : file;
                    Entry entry = entries.computeIfAbsent(frame, ignored -> new Entry());
                    if (refinement) {
                        entry.refinement = file;
                        entry.refinementBytes = bytes;
                    } else {
                        entry.frame = file;
                        entry.frameBytes = bytes;
                    }
                    entry.add(modified);
                } catch (NoSuchFileException ignored) {
                    // Removed concurrently, for example by another application instance.
                }
            }
        }
        List<Entry> ordered = new ArrayList<>(entries.values());
        ordered.sort(Comparator.comparing((Entry entry) -> !entry.kept())
                .thenComparing(entry -> entry.modified, Comparator.reverseOrder()));
        long retained = 0L;
        for (Entry entry : ordered) {
            if (entry.frame == null) {
                deleteQuietly(entry.refinement);
            } else if (retained + entry.frameBytes + entry.refinementBytes <= maxBytes) {
                retained += entry.frameBytes + entry.refinementBytes;
            } else if (retained + entry.frameBytes <= maxBytes) {
                retained += entry.frameBytes;
                if (entry.refinement != null) deleteQuietly(entry.refinement);
            } else {
                deleteQuietly(entry.frame);
                if (entry.refinement != null) deleteQuietly(entry.refinement);
            }
        }
    }

    /** Removes temporary files left by interrupted writes, sparing any still being written. */
    private void deleteStaleTemporaries() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        FileTime staleBefore = FileTime.from(Instant.now().minus(STALE_TEMPORARY_AGE));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + TEMPORARY_SUFFIX)) {
            for (Path file : files) {
                try {
                    if (Files.getLastModifiedTime(file).compareTo(staleBefore) < 0) {
                        deleteQuietly(file);
                    }
                } catch (IOException ignored) {
                    // Another instance may have removed or published it.
                }
            }
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "Could not scan the frame store: " + exception.getMessage());
        }
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // Recency only orders eviction; a stale time is harmless.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "Could not delete a frame store file: " + exception.getMessage());
        }
    }

    Path fileFor(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + SUFFIX);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    Path fileFor(RenderJob job) {
        return fileFor(keyText(job));
    }

    /** The antialiasing file stored beside a base frame file. */
    static Path refinementFileFor(Path frameFile) {
        String name = frameFile.getFileName().toString();
        String stem = name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
        return frameFile.resolveSibling(stem + REFINEMENT_SUFFIX);
    }

    private static Path frameFileFor(Path refinementFile) {
        String name = refinementFile.getFileName().toString();
        return refinementFile.resolveSibling(
                name.substring(0, name.length() - REFINEMENT_SUFFIX.length()) + SUFFIX);
    }

    /**
     * Exact identity of the samples a job calculates on its fresh grid. Decimal
     * coordinates keep their scale and doubles their bit patterns. The viewport's working
     * precision enters only through the derived render grid, which is what the backends
     * calculate from, so a view restored from scene JSON matches the navigated view it
     * was saved from whenever their grids are identical.
     */
    static String keyText(RenderJob job) {
        FormulaDefinition formula = job.formula();
        StringBuilder key = new StringBuilder()
                .append("formula=").append(formula.id()).append('\n')
                .append("preset=").append(formula.preset().name()).append('\n')
                .append("orbitTrap=").append(formula.orbitTrap().name()).append('\n');
        for (Map.Entry<String, Double> parameter : new TreeMap<>(formula.parameters()).entrySet()) {
            key.append("parameter.").append(parameter.getKey()).append('=')
                    .append(bits(parameter.getValue())).append('\n');
        }
        return key.append("sampleAccuracy=").append(job.sampleAccuracy().name()).append('\n')
                .append("centerReal=").append(job.viewport().center().real()).append('\n')
                .append("centerImaginary=").append(job.viewport().center().imaginary()).append('\n')
                .append("scale=").append(job.viewport().scaleExact()).append('\n')
                .append("grid=").append(gridText(RenderGrid.from(job.viewport(), job.width(), job.height())))
                .append('\n')
                .append("width=").append(job.width()).append('\n')
                .append("height=").append(job.height()).append('\n')
                .append("maxIterations=").append(job.maxIterations()).append('\n')
                .toString();
    }

    static String gridText(RenderGrid grid) {
        StringBuilder text = new StringBuilder()
                .append(bits(grid.originReal())).append(',')
                .append(bits(grid.originImaginary())).append(',')
                .append(bits(grid.realStep())).append(',')
                .append(bits(grid.imaginaryStep())).append(',')
                .append(grid.offsetX()).append(',')
                .append(grid.offsetY()).append(',')
                .append(grid.conjugateHeight());
        PreciseRenderGrid precise = grid.preciseGrid();
        if (precise != null) {
            text.append(';').append(decimal(precise.originReal()))
                    .append(',').append(decimal(precise.originImaginary()))
                    .append(',').append(decimal(precise.realStep()))
                    .append(',').append(decimal(precise.imaginaryStep()))
                    .append(',').append(precise.offsetX())
                    .append(',').append(precise.offsetY())
                    .append(',').append(precise.conjugateHeight())
                    .append(',').append(precise.mathContext());
        }
        return text.toString();
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static String decimal(BigDecimal value) {
        return value.toString();
    }

    /** A stored file that must not be used and is deleted. */
    private static final class InvalidEntryException extends IOException {
        private InvalidEntryException(String message) {
            super(message);
        }
    }
}
