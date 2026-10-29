package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractalDataOrbitTrapTest {

    @Test
    void frameWithoutOrbitTrapDoesNotAllocateDistanceStorage() throws Exception {
        RenderFrame frame = RenderFrame.create(job(OrbitTrap.NONE));
        FractalData data = (FractalData) frame.samplePlane();

        for (int index = 0; index < data.size(); index++) {
            assertTrue(Double.isNaN(data.orbitTrapDistance(index)));
        }
        // Frame creation must not allocate or fill the distance plane: that cost
        // ran on the FX thread for every fresh full-size frame.
        assertNull(distanceStorage(data));
    }

    @Test
    void frameWithOrbitTrapStoresDistances() throws Exception {
        RenderFrame frame = RenderFrame.create(job(OrbitTrap.CROSS));
        FractalData data = (FractalData) frame.samplePlane();

        assertNotNull(distanceStorage(data));
        assertTrue(Double.isNaN(data.orbitTrapDistance(3)));
        data.setValues(3, 7, 7.5, true, 0.25);
        assertEquals(0.25, data.orbitTrapDistance(3));
    }

    @Test
    void customFormulaWithoutTrapKeepsItsReportedDistances() throws Exception {
        // A custom formula may report distances without an orbit trap; nothing in
        // FractalFormula forbids it, so its frames must keep the storage.
        FractalFormula reportsDistance = (real, imaginary, maxIterations) ->
                new FractalSample(3, true, 4.0, 0.0, 2.0, 0.25);
        RenderJob job = new RenderJob(FormulaDefinition.custom(reportsDistance, OrbitTrap.NONE),
                new Viewport("-0.5", "0", "3"), 8, 6, 100);

        try (var backend = new DirectDoubleRenderBackend()) {
            RenderFrame frame = backend.render(RenderFrame.create(job), () -> false, region -> {}, null);

            assertTrue(frame.isComplete());
            assertEquals(0.25, frame.samplePlane().orbitTrapDistance(0));
            assertEquals(0.25, frame.samplePlane().orbitTrapDistance(job.width() * job.height() - 1));
        }
    }

    @Test
    void frameWithoutStorageAcceptsNaNAndRejectsFiniteDistances() {
        FractalData data = new FractalData(4, 3, 100, false);

        data.setValues(5, 7, 7.5, true, Double.NaN);
        assertEquals(7, data.iterations(5));
        assertTrue(Double.isNaN(data.orbitTrapDistance(5)));

        // A finite distance would be silently lost, so it must fail loudly.
        assertThrows(IllegalStateException.class, () -> data.setValues(5, 7, 7.5, true, 0.25));
    }

    @Test
    void copiesBetweenStoringFramesCarryDistances() {
        FractalData source = filledWithDistances();

        FractalData pixel = new FractalData(4, 3, 100, true);
        pixel.copyPixelFrom(source, 1, 2, 3, 0);
        assertEquals(9.5, pixel.orbitTrapDistance(3));
        assertTrue(Double.isNaN(pixel.orbitTrapDistance(2)));

        FractalData row = new FractalData(4, 3, 100, true);
        row.copyRowFrom(source, 1, 2, 1, 3);
        assertEquals(5.5, row.orbitTrapDistance(9));
        assertEquals(6.5, row.orbitTrapDistance(10));
        assertTrue(Double.isNaN(row.orbitTrapDistance(8)));

        FractalData region = new FractalData(4, 3, 100, true);
        region.copyRegionFrom(source, 2, 1, 0, 0, 2, 2);
        assertEquals(6.5, region.orbitTrapDistance(0));
        assertEquals(7.5, region.orbitTrapDistance(1));
        assertEquals(10.5, region.orbitTrapDistance(4));
        assertEquals(11.5, region.orbitTrapDistance(5));
        assertTrue(Double.isNaN(region.orbitTrapDistance(2)));
    }

    @Test
    void copiesFromFrameWithoutStorageClearStaleDistances() {
        FractalData target = filledWithDistances();
        FractalData withoutStorage = new FractalData(4, 3, 100, false);

        target.copyPixelFrom(withoutStorage, 0, 0, 0, 0);
        target.copyRowFrom(withoutStorage, 0, 1, 0, 2);
        target.copyRegionFrom(withoutStorage, 0, 0, 2, 2, 2, 1);

        assertTrue(Double.isNaN(target.orbitTrapDistance(0)));
        assertTrue(Double.isNaN(target.orbitTrapDistance(4)));
        assertTrue(Double.isNaN(target.orbitTrapDistance(5)));
        assertTrue(Double.isNaN(target.orbitTrapDistance(10)));
        assertTrue(Double.isNaN(target.orbitTrapDistance(11)));
        assertEquals(1.5, target.orbitTrapDistance(1));
        assertEquals(6.5, target.orbitTrapDistance(6));
        assertEquals(9.5, target.orbitTrapDistance(9));
    }

    @Test
    void copiesIntoFrameWithoutStorageRejectFiniteDistances() {
        FractalData withoutStorage = new FractalData(4, 3, 100, false);
        FractalData nanSource = new FractalData(4, 3, 100, true);

        withoutStorage.copyRegionFrom(nanSource, 0, 0, 0, 0, 4, 3);
        assertTrue(Double.isNaN(withoutStorage.orbitTrapDistance(0)));

        assertThrows(IllegalStateException.class,
                () -> withoutStorage.copyRowFrom(filledWithDistances(), 1, 1, 0, 4));
    }

    private static FractalData filledWithDistances() {
        FractalData data = new FractalData(4, 3, 100, true);
        for (int index = 0; index < data.size(); index++) {
            data.setValues(index, index, index, true, index + 0.5);
        }
        return data;
    }

    private static RenderJob job(OrbitTrap trap) {
        return new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, trap),
                new Viewport("-0.5", "0", "3"),
                8, 6, 100);
    }

    private static Object distanceStorage(FractalData data) throws Exception {
        var field = FractalData.class.getDeclaredField("orbitTrapDistances");
        field.setAccessible(true);
        return field.get(data);
    }
}
