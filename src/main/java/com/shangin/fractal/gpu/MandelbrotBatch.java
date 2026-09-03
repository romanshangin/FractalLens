package com.shangin.fractal.gpu;

/** One bounded native request. The backend owns it until readback completes. */
public final class MandelbrotBatch {
    final float[] input = new float[VulkanMandelbrotKernel.CAPACITY * VulkanMandelbrotKernel.INPUT_WORDS];
    final int[] output = new int[VulkanMandelbrotKernel.CAPACITY * VulkanMandelbrotKernel.OUTPUT_WORDS];
    final int[] pixels = new int[VulkanMandelbrotKernel.CAPACITY];
    MandelbrotTiming timing;
    int count;
    int maxIterations;
}
