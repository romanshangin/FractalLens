package com.shangin.fractal.render;

import com.shangin.fractal.export.InteractiveAntialiasService;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Allocation diagnostic only; run separately from profiler-free JavaFX timing. */
public final class AaAllocationBenchmark {
    private AaAllocationBenchmark() {}

    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
        Object bean = ManagementFactory.getThreadMXBean();
        if (!(boolean) type.getMethod("isThreadAllocatedMemorySupported").invoke(bean))
            throw new IllegalStateException("Thread allocation accounting is unavailable");
        type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
        var allocated = type.getMethod("getTotalThreadAllocatedBytes");
        int warmups = Integer.getInteger("baseline.warmups", 3);
        int runs = Integer.getInteger("baseline.runs", 5);
        Path output = Path.of(System.getProperty("baseline.output"));
        Files.createDirectory(output);
        try (var out = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")))) {
            out.println("fixture,width,height,phase,run,allocated_bytes,cache_bytes,argb_hash,sample_hash");
            for (var fixture : BaselineBenchmark.selectedFixtures()) {
                var step = fixture.steps().getFirst();
                for (int run = -warmups - 1; run < runs; run++) {
                    try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
                        var frame = RenderFrame.create(step.job());
                        backend.render(frame, () -> false, ignored -> {}, null);
                        var colors = new int[step.job().width() * step.job().height()];
                        var coloring = step.coloring(frame);
                        new FractalColorizer().color(frame.samplePlane(), IntBuffer.wrap(colors), coloring);
                        var retained = RefinedPixelSnapshot.empty(frame.job().width(), frame.job().height());
                        var done = new CompletableFuture<Void>();
                        java.util.function.BiConsumer<RenderRegion, int[]> publish = (region, pixels) -> {
                            for (int y = 0; y < region.height(); y++) System.arraycopy(pixels, y * region.width(),
                                    colors, (region.y() + y) * frame.job().width() + region.x(), region.width());
                        };
                        long before = (long) allocated.invoke(bean);
                        if (backend.select(frame.job()) instanceof MandelbrotPerturbationRenderBackend) {
                            aa.refineDeep(frame, coloring, retained, Runnable::run, publish,
                                    () -> done.complete(null), done::completeExceptionally);
                        } else aa.refine(frame, coloring, retained, Runnable::run, publish,
                                () -> done.complete(null), done::completeExceptionally);
                        done.get(180, TimeUnit.SECONDS);
                        long bytes = (long) allocated.invoke(bean) - before;
                        if (bytes < 0) throw new IllegalStateException("Non-monotonic allocation counter");
                        var field = aa.getClass().getDeclaredField("sampleCache");
                        field.setAccessible(true);
                        var cache = (AntialiasSampleCache) field.get(aa);
                        if (cache.usedBytes() > cache.maxBytes()) throw new IllegalStateException("AA cache budget exceeded");
                        String phase = run == -warmups - 1 ? "first" : run < 0 ? "warmup" : "sample";
                        out.println(String.join(",", fixture.id(), "" + frame.job().width(), "" + frame.job().height(),
                                phase, "" + run, "" + bytes, "" + cache.usedBytes(), "" + Arrays.hashCode(colors),
                                "" + BaselineBenchmark.sampleHash(frame)));
                        out.flush();
                    }
                }
                System.out.println("Measured " + fixture.id() + " allocations");
            }
            if (out.checkError()) throw new IllegalStateException("Allocation CSV write failure");
        }
        Files.writeString(output.resolve("COMPLETE"), "Allocation diagnostic completed; compare every final fingerprint with control.\n");
    }
}
