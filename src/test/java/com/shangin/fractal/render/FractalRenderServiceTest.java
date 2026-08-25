package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
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
    private RenderRequest request;

    @BeforeEach
    void setUp() {
        FractalPreset preset = FractalPreset.MANDELBROT;

        FractalCalculator calculator = new FractalCalculator(preset.createFormula());

        Viewport viewport = preset.defaultViewport();

        request = new RenderRequest(calculator, viewport, WIDTH, HEIGHT, MAX_ITERATIONS);

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

        renderService.render(request, DIRECT_EXECUTOR,

                progress -> regions.addAll(progress.regions()),

                data -> completed.countDown(),

                throwable -> {
                    error.set(throwable);
                    completed.countDown();
                });

        assertTrue(completed.await(5, TimeUnit.SECONDS), "Render did not complete");

        assertNull(error.get());

        int renderedPixels = regions.stream().mapToInt(region -> region.width() * region.height()).sum();

        assertEquals(WIDTH * HEIGHT, renderedPixels);
    }

    @Test
    void shouldBatchCompletedRegions() throws InterruptedException {

        List<RenderProgressBatch> batches = new CopyOnWriteArrayList<>();

        CountDownLatch completed = new CountDownLatch(1);

        renderService.render(
                request,
                DIRECT_EXECUTOR,
                batches::add,
                data -> completed.countDown(),
                Assertions::fail);

        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertFalse(batches.isEmpty());

        int totalRegions = batches.stream().mapToInt(batch -> batch.regions().size()).sum();

        assertTrue(totalRegions > batches.size(), "Expected at least some regions to be batched");

        assertTrue(batches.stream().noneMatch(batch -> batch.regions().isEmpty()));
    }

    @Test
    void shouldDeliverSuccessAfterAllProgress() throws InterruptedException {

        List<String> events = new CopyOnWriteArrayList<>();

        CountDownLatch completed = new CountDownLatch(1);

        renderService.render(request, DIRECT_EXECUTOR,

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
    void progressShouldReferToSameDataAsCompletedRender() throws InterruptedException {

        AtomicReference<FractalData> progressData = new AtomicReference<>();

        AtomicReference<FractalData> completedData = new AtomicReference<>();

        CountDownLatch completed = new CountDownLatch(1);

        renderService.render(request, DIRECT_EXECUTOR,

                progress -> progressData.compareAndSet(null, progress.data()),

                data -> {
                    completedData.set(data);
                    completed.countDown();
                },

                Assertions::fail);

        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertNotNull(progressData.get());
        assertNotNull(completedData.get());

        assertSame(progressData.get(), completedData.get());
    }
}