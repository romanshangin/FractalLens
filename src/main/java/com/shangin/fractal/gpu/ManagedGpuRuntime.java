package com.shangin.fractal.gpu;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Synchronizes native use and destruction, without any graphics-API imports. */
final class ManagedGpuRuntime implements GpuRuntime {
    private final GpuPlatform platform;
    private final List<GpuDevice> devices;
    private final GpuDevice selectedDevice;
    private GpuSession session;
    private boolean executing;
    private GpuRuntimeState state = GpuRuntimeState.AVAILABLE;
    private String detail = "Compute device ready; palette kernel is opt-in.";

    private ManagedGpuRuntime(GpuPlatform platform, GpuSession session) {
        this.platform = platform;
        this.session = session;
        devices = List.copyOf(session.devices());
        selectedDevice = Objects.requireNonNull(session.selectedDevice());
        capabilityReport(); // Validate that the selected device is usable.
    }

    static GpuRuntime open(GpuPlatform platform, Supplier<GpuSession> opener) {
        GpuSession session = null;
        try {
            session = opener.get();
            session.checkHealth();
            return new ManagedGpuRuntime(platform, session);
        } catch (RuntimeException | LinkageError failure) {
            if (session != null) {
                try {
                    session.close(failure instanceof GpuException gpu && gpu.deviceLost());
                } catch (RuntimeException | LinkageError cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            return new UnavailableGpuRuntime(platform, "GPU initialization failed: " + describe(failure));
        }
    }

    @Override
    public synchronized GpuCapabilityReport capabilityReport() {
        return new GpuCapabilityReport(platform, state, devices, Optional.of(selectedDevice), detail);
    }

    @Override
    public PaletteRecolorTiming recolorPalette(Supplier<PaletteRecolorRequest> request,
                                               GpuWork<PaletteRecolorTiming> cpu)
            throws InterruptedException {
        return runOrFallback(GpuNumericCapability.FLOAT32,
                () -> {
                    long started = System.nanoTime();
                    PaletteRecolorRequest prepared = request.get();
                    long ready = System.nanoTime();
                    PaletteRecolorTiming timing = session.recolorPalette(prepared);
                    return timing.withPreparation(ready - started, System.nanoTime() - started);
                }, cpu);
    }

    @Override
    public synchronized boolean checkHealth() {
        requireOutsideOperation();
        if (state != GpuRuntimeState.AVAILABLE) {
            return false;
        }
        try {
            session.checkHealth();
            return true;
        } catch (GpuException failure) {
            disable(failure);
            return false;
        }
    }

    @Override
    public <T> T runOrFallback(GpuNumericCapability required, GpuWork<T> gpu, GpuWork<T> cpu)
            throws InterruptedException {
        Objects.requireNonNull(required);
        Objects.requireNonNull(gpu);
        Objects.requireNonNull(cpu);
        synchronized (this) {
            requireOutsideOperation();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("GPU operation cancelled");
            }
            if (isUsableFor(required)) {
                try {
                    executing = true;
                    try {
                        return gpu.run();
                    } finally {
                        executing = false;
                    }
                } catch (GpuException failure) {
                    disable(failure);
                }
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("CPU fallback cancelled");
        }
        return cpu.run();
    }

    @Override
    public synchronized void handleDeviceLoss(Throwable cause) {
        requireOutsideOperation();
        if (state == GpuRuntimeState.AVAILABLE) {
            disable(new GpuException("GPU device lost: " + describe(cause), true));
        }
    }

    private void disable(GpuException failure) {
        state = failure.deviceLost() ? GpuRuntimeState.DEVICE_LOST : GpuRuntimeState.UNAVAILABLE;
        detail = describe(failure);
        release(failure.deviceLost());
    }

    @Override
    public synchronized void close() {
        requireOutsideOperation();
        if (state != GpuRuntimeState.CLOSED) {
            boolean lost = state == GpuRuntimeState.DEVICE_LOST;
            if (state == GpuRuntimeState.AVAILABLE) {
                detail = "GPU runtime closed.";
            }
            state = GpuRuntimeState.CLOSED;
            release(lost);
        }
    }

    private void requireOutsideOperation() {
        if (executing) {
            throw new IllegalStateException("GPU work must not re-enter lifecycle or execution methods");
        }
    }

    private void release(boolean deviceLost) {
        GpuSession owned = session;
        session = null;
        if (owned != null) {
            try {
                owned.close(deviceLost);
            } catch (RuntimeException | LinkageError cleanup) {
                detail += "; cleanup failed: " + describe(cleanup);
            }
        }
    }

    static String describe(Throwable cause) {
        return cause == null ? "unknown cause"
                : cause.getClass().getSimpleName() + ": " + Objects.toString(cause.getMessage(), "no detail");
    }
}
