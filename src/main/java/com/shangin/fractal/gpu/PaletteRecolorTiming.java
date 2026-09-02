package com.shangin.fractal.gpu;

/** Host wall times. Dispatch includes encoding, submission and fence wait, not just shader time. */
public record PaletteRecolorTiming(long preparationNanos, long uploadNanos, long dispatchNanos,
                                   long readbackNanos, long totalNanos, long javafxQueueNanos,
                                   long presentationNanos, long uploadedBytes, boolean gpuUsed) {
    public static PaletteRecolorTiming cpu(long preparation, long work, long total) {
        return new PaletteRecolorTiming(preparation, 0, work, 0, total, 0, 0, 0, false);
    }

    public PaletteRecolorTiming withPreparation(long extra, long total) {
        return new PaletteRecolorTiming(preparationNanos + extra, uploadNanos, dispatchNanos,
                readbackNanos, total, javafxQueueNanos, presentationNanos, uploadedBytes, gpuUsed);
    }

    /** Presentation is the array copy + PixelBuffer update; it is not display/scanout latency. */
    public PaletteRecolorTiming withPublication(long queue, long copyAndUpdate) {
        return new PaletteRecolorTiming(preparationNanos, uploadNanos, dispatchNanos,
                readbackNanos, totalNanos, queue, copyAndUpdate, uploadedBytes, gpuUsed);
    }

    public long endToEndNanos() { return totalNanos + javafxQueueNanos + presentationNanos; }
}
