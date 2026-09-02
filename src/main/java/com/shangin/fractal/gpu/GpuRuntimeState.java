package com.shangin.fractal.gpu;

/** Lifecycle state visible to the render backend selector. */
public enum GpuRuntimeState {
    AVAILABLE,
    UNAVAILABLE,
    DEVICE_LOST,
    CLOSED
}
