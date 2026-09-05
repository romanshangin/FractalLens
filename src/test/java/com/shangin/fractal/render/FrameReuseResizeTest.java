package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FrameReuseResizeTest {
    @Test
    void expansionComputesOnlyBordersAndCropComputesNothing() throws Exception {
        verifyResize(new Viewport("-0.75", "0.1", "0.1"));
        verifyResize(new Viewport("-0.743643887037151", "0.13182590420533", "1e-40"));
    }

    private void verifyResize(Viewport viewport) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FractalCalculator calculator = new FractalCalculator((real, imaginary, limit) -> {
            calls.incrementAndGet();
            return new FractalSample(5, true, 5.5, real);
        });
        FrameReusePlanner planner = new FrameReusePlanner();
        RenderFrame source = RenderFrame.create(new RenderRequest(calculator, viewport, 40, 30, 300));
        try (ParallelFractalCalculator workers = new ParallelFractalCalculator(2)) {
            workers.calculate(source, () -> false, ignored -> {});
            calls.set(0);
            Viewport expandedViewport = new Viewport(viewport.center(),
                    viewport.imaginaryUnitsPerPixelExact(30).multiply(
                            BigDecimal.valueOf(49), viewport.mathContext()));
            FrameReuseResult expanded = planner.plan(source,
                    new RenderRequest(calculator, expandedViewport, 60, 50, 300));
            assertEquals(new PixelShift(10, 10), expanded.shift().orElseThrow());
            assertEquals(40 * 30, expanded.reusedPixels());
            for (int y = 0; y < 30; y++) {
                for (int x = 0; x < 40; x++) {
                    assertEquals(source.renderGrid().realAt(x), expanded.frame().renderGrid().realAt(x + 10));
                    assertEquals(source.renderGrid().imaginaryAt(y), expanded.frame().renderGrid().imaginaryAt(y + 10));
                    assertEquals(source.renderGrid().preciseGrid().realAt(x),
                            expanded.frame().renderGrid().preciseGrid().realAt(x + 10));
                }
            }
            workers.calculate(expanded.frame(), () -> false, ignored -> {});
            assertEquals(60 * 50 - 40 * 30, calls.get());

            calls.set(0);
            PixelShift expandedPanShift = new PixelShift(7, -4);
            Viewport expandedPanViewport = expanded.frame().request().viewport()
                    .shiftedByPixels(expandedPanShift.dx(), expandedPanShift.dy(), 60, 50);
            FrameReuseResult expandedPan = planner.plan(expanded.frame(),
                    new RenderRequest(calculator, expandedPanViewport, 60, 50, 300));
            assertEquals(expandedPanShift, expandedPan.shift().orElseThrow());
            assertEquals((60 - 7) * (50 - 4), expandedPan.reusedPixels());
            workers.calculate(expandedPan.frame(), () -> false, ignored -> {});
            assertEquals(60 * 50 - expandedPan.reusedPixels(), calls.get(),
                    "A pan after expansion must calculate only exposed edges");

            calls.set(0);
            FrameReuseResult cropped = planner.plan(expanded.frame(), source.request());
            assertTrue(cropped.frame().isComplete());
            assertEquals(new PixelShift(-10, -10), cropped.shift().orElseThrow());
            workers.calculate(cropped.frame(), () -> false, ignored -> {});
            assertEquals(0, calls.get());
            for (int i = 0; i < source.samplePlane().size(); i++) {
                assertEquals(source.samplePlane().smoothIterations(i), cropped.frame().samplePlane().smoothIterations(i));
            }

            calls.set(0);
            PixelShift croppedPanShift = new PixelShift(-5, 3);
            Viewport croppedPanViewport = cropped.frame().request().viewport()
                    .shiftedByPixels(croppedPanShift.dx(), croppedPanShift.dy(), 40, 30);
            FrameReuseResult croppedPan = planner.plan(cropped.frame(),
                    new RenderRequest(calculator, croppedPanViewport, 40, 30, 300));
            assertEquals(croppedPanShift, croppedPan.shift().orElseThrow());
            assertEquals((40 - 5) * (30 - 3), croppedPan.reusedPixels());
            workers.calculate(croppedPan.frame(), () -> false, ignored -> {});
            assertEquals(40 * 30 - croppedPan.reusedPixels(), calls.get(),
                    "A pan after cropping must calculate only exposed edges");
        }
    }

    @Test
    void partialResizeKeepsOnlyValidSamplesAndCanGrowOneAxisWhileCroppingTheOther() {
        FractalCalculator calculator = new FractalCalculator((r, i, limit) -> new FractalSample(1, true, 1, 0));
        Viewport viewport = new Viewport("0", "0.2", "1e-12");
        RenderFrame source = RenderFrame.create(new RenderRequest(calculator, viewport, 40, 30, 300));
        source.validity().markReady(new RenderRegion(8, 2, 20, 10));
        Viewport target = new Viewport(viewport.center(), viewport.imaginaryUnitsPerPixelExact(30)
                .multiply(BigDecimal.valueOf(49), viewport.mathContext()));
        FrameReuseResult reuse = new FrameReusePlanner().plan(source,
                new RenderRequest(calculator, target, 20, 50, 300));
        assertEquals(new PixelShift(-10, 10), reuse.shift().orElseThrow());
        assertEquals(18 * 10, reuse.reusedPixels());
        assertTrue(reuse.frame().validity().isRegionReady(new RenderRegion(0, 12, 18, 10)));
        assertFalse(reuse.frame().validity().isReady(19, 12));
    }
}
