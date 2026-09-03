package com.shangin.fractal.gpu;

import java.util.List;

/** Internal native ownership boundary, replaceable in hardware-independent tests. */
interface GpuSession {
    List<GpuDevice> devices();
    GpuDevice selectedDevice();
    void checkHealth();
    default PaletteRecolorTiming recolorPalette(PaletteRecolorRequest request) throws InterruptedException {
        throw new GpuException("Palette kernel unavailable", false);
    }
    void close(boolean deviceLost);
    default void calculateMandelbrot(MandelbrotBatch batch) throws InterruptedException {
        throw new GpuException("Mandelbrot kernel unavailable", false);
    }
}
