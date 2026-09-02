package com.shangin.fractal.gpu;

/** A synchronous operation which preserves cooperative cancellation. */
@FunctionalInterface
public interface GpuWork<T> {
    T run() throws InterruptedException;
}
