package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FractalRenderServiceTest {

    private static final int WIDTH = 300;
    private static final int HEIGHT = 200;
    private static final int MAX_ITERATIONS = 300;

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private FractalRenderService renderService;
    private RenderFrame renderFrame;

    @BeforeEach
    void setUp() {
        FractalPreset preset = FractalPreset.MANDELBROT;

        FractalCalculator calculator = new FractalCalculator(preset.createFormula());

        Viewport viewport = preset.defaultViewport();

        renderFrame = RenderFrame.create(new RenderRequest(calculator, viewport, WIDTH, HEIGHT, MAX_ITERATIONS));

        renderService = new FractalRenderService();
    }

    @AfterEach
    void tearDown() {
        renderService.close();
    }

    @Test
    void shouldDeliverAllRenderedRegions() throws InterruptedException {

        List<RenderRegion> regions = new CopyOnWriteArrayList<>();

        CountDownLatch completed = new CountDownLatch(1);

        AtomicReference<Throwable> error = new AtomicReference<>();

        renderService.render(renderFrame, DIRECT_EXECUTOR,

                progress -> regions.addAll(progress.regions()),

                data -> completed.countDown(),

                throwable -> {
                    error.set(throwable);
                    completed.countDown();
                });

        assertTrue(completed.await(5, TimeUnit.SECONDS), "Render did not complete");

        assertNull(error.get());

        int renderedPixels = regions.stream().mapToInt(region -> region.width() * region.height()).sum();

        assertTrue(renderFrame.isComplete());

        assertEquals(
                WIDTH * HEIGHT,
                renderFrame.validity().readyPixelCount());
    }

    @Test
    void shouldBatchCompletedRegions() throws InterruptedException {

        List<RenderProgressBatch> batches = new CopyOnWriteArrayList<>();

        CountDownLatch completed = new CountDownLatch(1);

        AtomicReference<Throwable> error = new AtomicReference<>();

        renderService.render(
                renderFrame,
                DIRECT_EXECUTOR,
                batches::add,

                frame ->
                        completed.countDown(),

                throwable -> {
                    error.set(throwable);
                    completed.countDown();
                }
        );

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertNull(error.get());
        assertFalse(batches.isEmpty());

        int totalRegions = batches.stream().mapToInt(batch -> batch.regions().size()).sum();

        assertTrue(totalRegions > batches.size(), "Expected at least some regions to be batched");

        assertTrue(batches.stream().noneMatch(batch -> batch.regions().isEmpty()));
    }

    @Test
    void shouldDeliverSuccessAfterAllProgress() throws InterruptedException {

        List<String> events = new CopyOnWriteArrayList<>();

        CountDownLatch completed = new CountDownLatch(1);

        renderService.render(renderFrame, DIRECT_EXECUTOR,

                progress -> events.add("progress"),

                data -> {
                    events.add("success");
                    completed.countDown();
                },

                throwable -> {
                    events.add("error");
                    completed.countDown();
                });

        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertFalse(events.isEmpty());

        assertEquals("success", events.getLast());

        assertFalse(events.contains("error"));
    }

    @Test
    void progressShouldReferToSameFrameAsCompletedRender()
            throws InterruptedException {

        AtomicReference<RenderFrame> progressFrame =
                new AtomicReference<>();

        AtomicReference<RenderFrame> completedFrame =
                new AtomicReference<>();

        AtomicReference<Throwable> error =
                new AtomicReference<>();

        CountDownLatch completed =
                new CountDownLatch(1);

        renderService.render(
                renderFrame,
                DIRECT_EXECUTOR,

                progress ->
                        progressFrame.compareAndSet(
                                null,
                                progress.frame()
                        ),

                frame -> {
                    completedFrame.set(frame);
                    completed.countDown();
                },

                throwable -> {
                    error.set(throwable);
                    completed.countDown();
                }
        );

        assertTrue(
                completed.await(
                        5,
                        TimeUnit.SECONDS
                )
        );

        assertNull(error.get());

        assertNotNull(progressFrame.get());
        assertNotNull(completedFrame.get());

        assertSame(
                renderFrame,
                progressFrame.get()
        );

        assertSame(
                renderFrame,
                completedFrame.get()
        );

        assertSame(
                progressFrame.get(),
                completedFrame.get()
        );
    }
}