package com.shangin.fractal.gpu;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class CompiledShaderTest {
    @Test
    void rejectsMissingShaderBytesWithGpuFallbackFailure() {
        for (ByteBuffer code : new ByteBuffer[] {null, ByteBuffer.allocate(0)}) {
            GpuException failure = assertThrows(GpuException.class,
                    () -> CompiledShader.requireBytes(code, "test.comp"));
            assertFalse(failure.deviceLost());
            assertTrue(failure.getMessage().contains("test.comp"));
        }
    }

    @Test
    void preservesNonemptyShaderBuffer() {
        ByteBuffer code = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        code.position(1);
        assertSame(code, CompiledShader.requireBytes(code, "test.comp"));
        assertEquals(1, code.position());
    }
}
