package com.shangin.fractal.gpu;

/** A native GPU failure safe to route to the matching CPU operation. */
public final class GpuException extends RuntimeException {
    private final boolean deviceLost;

    public GpuException(String message, boolean deviceLost) {
        super(message);
        this.deviceLost = deviceLost;
    }

    public boolean deviceLost() {
        return deviceLost;
    }
}
