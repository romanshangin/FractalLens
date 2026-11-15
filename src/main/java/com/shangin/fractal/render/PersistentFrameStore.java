package com.shangin.fractal.render;

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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Antialiasing is not stored.
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
     */
    static final int CALCULATION_VERSION = 1;

    private static final byte[] MAGIC = "FLFRAME\0".getBytes(StandardCharsets.US_ASCII);
    static final String SUFFIX = ".frame";
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final int MAX_TEXT_BYTES = 1 << 20;
    private static final int CHUNK_BYTES = 1 << 20;
    private static final System.Logger LOGGER = System.getLogger(PersistentFrameStore.class.getName());

    private final Path directory;
    private final long maxBytes;
    private final long settleDelayNanos;
    private final ScheduledThreadPoolExecutor writer;
    private final AtomicLong temporaryCounter = new AtomicLong();
    private final AtomicBoolean writeFailureLogged = new AtomicBoolean();
    private final Object lock = new Object();
    private PendingSave pending;
    private boolean closed;

    private static final class PendingSave {
        private final RenderFrame frame;
        private ScheduledFuture<?> future;

        private PendingSave(RenderFrame frame) {
            this.frame = frame;
        }
    }

    public PersistentFrameStore(Path directory) {
        this(directory, DEFAULT_MAX_BYTES, DEFAULT_SETTLE_DELAY);
    }

    PersistentFrameStore(Path directory, long maxBytes, Duration settleDelay) {
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
            PendingSave next = new PendingSave(frame);
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
     * Writes the frame still waiting for its settle delay, then stops the writer. Waits
     * a bounded time; an interrupted write leaves only a temporary file, which is never
     * read and is removed by a later store. Idempotent.
     */
    @Override
    public void close() {
        PendingSave flush;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            flush = pending;
            pending = null;
        }
        if (flush != null) {
            flush.future.cancel(false);
            writer.execute(() -> write(flush.frame));
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
            evict(target);
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            if (writeFailureLogged.compareAndSet(false, true)) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Could not store a completed frame; rendering continues without it: "
                                + exception.getMessage());
            }
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
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

    /** Deletes the least recently used entries until the store fits its budget. */
    private void evict(Path keep) throws IOException {
        record Entry(Path path, long bytes, FileTime modified) {}
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                try {
                    entries.add(new Entry(file, Files.size(file), Files.getLastModifiedTime(file)));
                } catch (NoSuchFileException ignored) {
                    // Removed concurrently, for example by another application instance.
                }
            }
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.path().equals(keep))
                .thenComparing(Entry::modified, Comparator.reverseOrder()));
        long retained = 0L;
        for (Entry entry : entries) {
            retained += entry.bytes();
            if (retained > maxBytes) {
                deleteQuietly(entry.path());
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
