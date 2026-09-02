package com.shangin.fractal.gpu;

import java.util.List;

/** Internal native ownership boundary, replaceable in hardware-independent tests. */
interface GpuSession {
    List<GpuDevice> devices();
    GpuDevice selectedDevice();
    void checkHealth();
    void close(boolean deviceLost);
}
