package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.scene.ColoringSettings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardware-independent runtime for Julia GPU mode tests. Every frame is one solid
 * color derived from the palette and the Julia constant, so tests can tell frames,
 * palettes and stale results apart without a GPU. Like {@link ManagedGpuRuntime} it
 * holds its monitor for the whole kernel call, which also guards the capability
 * report, while {@link #isUsableSnapshotFor} reads a volatile field without waiting.
 */
public final class FakeJuliaPreviewRuntime implements GpuRuntime {
    private volatile boolean available = true;
    private volatile boolean emptyResult;
    private volatile int maxFrameWidth = Integer.MAX_VALUE;
    private volatile int maxUngatedWidth = Integer.MAX_VALUE;
    private volatile int lastStartedWidth;
    private volatile RuntimeException failure;
    private volatile boolean gated;
    private volatile boolean isolatedBrightPixel;
    private final BlockingQueue<Object> releases = new LinkedBlockingQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger started = new AtomicInteger();
    private volatile boolean closed;
    private volatile int lastMaxIterations;
    private volatile boolean lastFilterBrightPoints;
    private volatile boolean gpuFilterRefused;

    /** The solid color a frame of this coloring and constant receives. */
    public static int expectedColor(ColoringSettings coloring, double cReal) {
        int palette = ((SmoothPaletteColoring) coloring.createStrategy()).palette().color(0.25);
        return 0xff000000 | ((palette ^ Float.hashCode((float) cReal) * 0x9e3779b9) & 0xffffff);
    }

    public void setAvailable(boolean available) { this.available = available; }
    /** The next calls return empty, like a runtime that disabled itself. */
    public void setEmptyResult(boolean empty) { emptyResult = empty; }
    public void refuseFramesWiderThan(int width) { maxFrameWidth = width; }
    public void gateFramesWiderThan(int width) { maxUngatedWidth = width; }
    public int lastStartedWidth() { return lastStartedWidth; }
    public void setFailure(RuntimeException failure) { this.failure = failure; }
    /** Each call waits for one {@link #release()} after it starts. */
    public void setGated(boolean gated) { this.gated = gated; }
    public void showIsolatedBrightPixel() { isolatedBrightPixel = true; }
    public void release() { releases.add(Boolean.TRUE); }
    public int calls() { return calls.get(); }
    public int started() { return started.get(); }
    public boolean closed() { return closed; }
    /** Iteration limit of the last frame the fake calculated. */
    public int lastMaxIterations() { return lastMaxIterations; }
    /** Whether the last calculated frame asked for the bright-point pass. */
    public boolean lastFilterBrightPoints() { return lastFilterBrightPoints; }
    /** Returns filter requests unfiltered, like a kernel whose filtered buffer exceeds its budget. */
    public void refuseGpuFilter(boolean refused) { gpuFilterRefused = refused; }

    private static final GpuDevice DEVICE = new GpuDevice("fake", "1.1", 0, 0, 0, 1024, 1L << 30,
            java.util.Set.of(GpuNumericCapability.FLOAT32));

    @Override
    public synchronized GpuCapabilityReport capabilityReport() {
        return new GpuCapabilityReport(GpuPlatform.MACOS,
                available ? GpuRuntimeState.AVAILABLE : GpuRuntimeState.UNAVAILABLE,
                List.of(DEVICE), Optional.of(DEVICE), "fake Julia preview runtime");
    }

    @Override public boolean isUsableFor(GpuNumericCapability capability) {
        return capabilityReport().supports(capability);
    }

    @Override public boolean isUsableSnapshotFor(GpuNumericCapability capability) {
        return available && capability == GpuNumericCapability.FLOAT32;
    }

    @Override public synchronized void handleDeviceLoss(Throwable cause) { available = false; }
    @Override public synchronized boolean checkHealth() { return available; }

    @Override
    public <T> T runOrFallback(GpuNumericCapability required, GpuWork<T> gpu, GpuWork<T> cpu)
            throws InterruptedException {
        return cpu.run();
    }

    @Override
    public synchronized Optional<GpuJuliaPreviewResult> renderJuliaPreview(GpuJuliaPreviewRequest request)
            throws InterruptedException {
        lastStartedWidth = request.width();
        started.incrementAndGet();
        if ((gated || request.width() > maxUngatedWidth) && releases.poll(15, TimeUnit.SECONDS) == null) {
            throw new IllegalStateException("Gated fake GPU call was never released");
        }
        calls.incrementAndGet();
        lastMaxIterations = request.maxIterations();
        lastFilterBrightPoints = request.filterBrightPoints();
        if (failure != null) throw failure;
        if (!available || emptyResult || request.width() > maxFrameWidth) {
            available = false;
            return Optional.empty();
        }
        int palette = request.palette().color(0.25);
        int color = 0xff000000 | ((palette ^ Float.hashCode(request.cReal()) * 0x9e3779b9) & 0xffffff);
        if (isolatedBrightPixel) {
            java.util.Arrays.fill(request.colors(), 0xff202020);
            request.colors()[request.width() + 1] = 0xffffffff;
        } else {
            java.util.Arrays.fill(request.colors(), color);
        }
        boolean filtered = request.filterBrightPoints() && !gpuFilterRefused;
        if (filtered) {
            // The real kernel's GPU pass matches this CPU reference bit for bit.
            int[] raw = request.colors().clone();
            JuliaBrightPointFilter.apply(raw, request.colors(), request.width(), request.height());
        }
        return Optional.of(new GpuJuliaPreviewResult(request.colors(), 0, 0, 1, 0, 1, 0, filtered));
    }

    @Override public synchronized void close() {
        available = false;
        closed = true;
    }
}
