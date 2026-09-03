package com.shangin.fractal.gpu;

/** Standalone diagnostic owner of the production kernel. */
final class VulkanMandelbrotProbe implements AutoCloseable {
    static final int CAPACITY = VulkanMandelbrotKernel.CAPACITY;
    static final int INPUT_WORDS = VulkanMandelbrotKernel.INPUT_WORDS;
    static final int OUTPUT_WORDS = VulkanMandelbrotKernel.OUTPUT_WORDS;
    private final ProbeDevice owner;
    private final VulkanMandelbrotKernel kernel;
    private boolean lost;
    boolean interruptAfterSubmit;

    private VulkanMandelbrotProbe(ProbeDevice owner, VulkanMandelbrotKernel kernel) {
        this.owner = owner;
        this.kernel = kernel;
    }

    static VulkanMandelbrotProbe open() {
        ProbeDevice owner = ProbeDevice.open();
        try {
            return new VulkanMandelbrotProbe(owner,
                    VulkanMandelbrotKernel.open(owner.device, owner.physical, owner.queue, owner.family));
        } catch (RuntimeException | LinkageError failure) {
            owner.close(failure instanceof GpuException e && e.deviceLost());
            throw failure;
        }
    }

    String description() { return owner.description + "; shaderSha256=" + kernel.shaderHash(); }

    void calculate(float[] input, int count, int limit, int[] output) throws InterruptedException {
        kernel.interruptAfterSubmit = interruptAfterSubmit;
        try { kernel.calculate(input, count, limit, output); }
        catch (GpuException failure) { lost |= failure.deviceLost(); throw failure; }
    }

    @Override public void close() {
        try { kernel.close(); } finally { owner.close(lost); }
    }
}
