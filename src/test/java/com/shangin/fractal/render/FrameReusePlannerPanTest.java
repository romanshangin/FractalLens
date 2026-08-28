package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameReusePlannerPanTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 700;
    private static final int MAX_ITERATIONS = 300;

    private FractalCalculator calculator;
    private ParallelFractalCalculator parallelCalculator;
    private FrameReusePlanner planner;

    private Viewport sourceViewport;
    private RenderRequest sourceRequest;

    @BeforeEach
    void setUp() {
        FractalPreset preset = FractalPreset.MANDELBROT;

        calculator = new FractalCalculator(preset.createFormula());

        parallelCalculator = new ParallelFractalCalculator(4);

        planner = new FrameReusePlanner();

        sourceViewport = preset.defaultViewport();

        sourceRequest = new RenderRequest(calculator, sourceViewport, WIDTH, HEIGHT, MAX_ITERATIONS);
    }

    @AfterEach
    void tearDown() {
        parallelCalculator.close();
    }

    @Test
    void pan01_50PixelsRight() throws InterruptedException {
        assertPanReuse(1, 50, 0, 665_000);
    }

    @Test
    void pan02_100PixelsRight() throws InterruptedException {
        assertPanReuse(2, 100, 0, 630_000);
    }

    @Test
    void pan03_200PixelsRight() throws InterruptedException {
        assertPanReuse(3, 200, 0, 560_000);
    }

    @Test
    void pan04_400PixelsRight() throws InterruptedException {
        assertPanReuse(4, 400, 0, 420_000);
    }

    @Test
    void pan05_50PixelsDown() throws InterruptedException {
        assertPanReuse(5, 0, 50, 650_000);
    }

    @Test
    void pan06_100PixelsDown() throws InterruptedException {
        assertPanReuse(6, 0, 100, 600_000);
    }

    @Test
    void pan07_200PixelsDown() throws InterruptedException {
        assertPanReuse(7, 0, 200, 500_000);
    }

    @Test
    void pan08_100PixelsRightAnd100Down() throws InterruptedException {

        assertPanReuse(8, 100, 100, 540_000);
    }

    @Test
    void pan09_300PixelsRightAnd200Down() throws InterruptedException {

        assertPanReuse(9, 300, 200, 350_000);
    }

    @Test
    void pan10_100PixelsLeft() throws InterruptedException {

        assertPanReuse(10, -100, 0, 630_000);
    }

    private void assertPanReuse(int testNumber, int dx, int dy, int expectedReusablePixels) throws InterruptedException {

        /*
         * Сначала полностью считаем source frame.
         *
         * Его calculation НЕ входит в измеряемое
         * время reuse.
         */
        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        assertTrue(sourceFrame.isComplete());

        /*
         * Создаём target viewport, сдвинутый
         * ровно на заданное количество pixels.
         */
        Viewport targetViewport = sourceViewport.shiftedByPixels(dx, dy, WIDTH, HEIGHT);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        /*
         * Измеряем только создание нового frame
         * и перенос reusable FractalData.
         */
        long start = System.nanoTime();

        RenderFrame targetFrame = planner.createFrame(sourceFrame, targetRequest);

        long elapsedNanos = System.nanoTime() - start;

        int actualReusablePixels = targetFrame.validity().readyPixelCount();

        int totalPixels = WIDTH * HEIGHT;

        double reusePercent = actualReusablePixels * 100.0 / totalPixels;

        double reuseMs = elapsedNanos / 1_000_000.0;

        /*
         * Проверяем сам reuse.
         */
        assertEquals(expectedReusablePixels, actualReusablePixels);

        assertFalse(targetFrame.isComplete());

        /*
         * Дополнительная страховка:
         *
         * теоретический overlap должен совпадать
         * с тем, что реально сделал planner.
         */
        int expectedFromFormula = (WIDTH - Math.abs(dx)) * (HEIGHT - Math.abs(dy));

        assertEquals(expectedFromFormula, actualReusablePixels);

        System.out.printf("PAN #%02d " + "shift=(%4d,%4d) " + "reused=%7d/%7d " + "(%6.2f%%) " + "dataReuse=%8.3f ms%n", testNumber, dx, dy, actualReusablePixels, totalPixels, reusePercent, reuseMs);
    }
}