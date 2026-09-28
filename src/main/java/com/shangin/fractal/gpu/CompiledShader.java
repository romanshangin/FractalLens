package com.shangin.fractal.gpu;

import java.nio.ByteBuffer;

/** Validates shaderc output before it is passed to Vulkan. */
final class CompiledShader {
    private CompiledShader() {}

    static ByteBuffer requireBytes(ByteBuffer code, String name) {
        if (code == null || !code.hasRemaining()) {
            throw new GpuException("Compiled shader has no SPIR-V bytes: " + name, false);
        }
        return code;
    }
}
