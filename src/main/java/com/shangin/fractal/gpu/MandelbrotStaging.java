package com.shangin.fractal.gpu;

/** Reusable primitive staging for one fully validated GPU region. */
final class MandelbrotStaging {
    private static final byte REJECTED = 0;
    private static final byte CERTIFIED_INTERIOR = 1;
    private static final byte CERTIFIED_ESCAPED = 2;
    private static final byte RECOVERED_INTERIOR = 3;
    private static final byte RECOVERED_ESCAPED = 4;

    final int[] iterations = new int[VulkanMandelbrotKernel.CAPACITY];
    final double[] smoothIterations = new double[VulkanMandelbrotKernel.CAPACITY];
    final byte[] states = new byte[VulkanMandelbrotKernel.CAPACITY];

    void reject(int index) {
        states[index] = REJECTED;
    }

    void certify(int index, int iteration, double smooth, boolean escaped) {
        iterations[index] = iteration;
        smoothIterations[index] = smooth;
        states[index] = escaped ? CERTIFIED_ESCAPED : CERTIFIED_INTERIOR;
    }

    void recover(int index, int iteration, double smooth, boolean escaped) {
        iterations[index] = iteration;
        smoothIterations[index] = smooth;
        states[index] = escaped ? RECOVERED_ESCAPED : RECOVERED_INTERIOR;
    }

    boolean rejected(int index) {
        return states[index] == REJECTED;
    }

    boolean escaped(int index) {
        return states[index] == CERTIFIED_ESCAPED || states[index] == RECOVERED_ESCAPED;
    }

    static long bytesPerBatch() {
        return (long) VulkanMandelbrotKernel.CAPACITY * (Integer.BYTES + Double.BYTES + Byte.BYTES);
    }
}
