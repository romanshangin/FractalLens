package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SpareFrameStorageTest {

    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Test
    void stableShapePreparesStorageThatTheNextFrameTakes() {
        try (var storage = new SpareFrameStorage(tasks::add)) {
            RenderJob job = job(40, 30, OrbitTrap.NONE, 100);
            RenderFrame first = create(storage, job);
            assertTrue(tasks.isEmpty(), "The first frame of a shape does not predict a stable size");

            storage.prepareFor(job);
            assertFalse(storage.hasSpareFor(job), "Preparation runs on the executor, not the caller");
            runTasks();
            assertTrue(storage.hasSpareFor(job));

            RenderFrame second = create(storage, job(40, 30, OrbitTrap.NONE, 250));
            assertFalse(storage.hasSpareFor(job), "A spare is handed to exactly one frame");
            assertEquals(1, tasks.size(), "Taking the spare prepares the next one");
            assertEmpty(second);
            assertEquals(250, second.fractalData().maxIterations());
            assertFalse(first.fractalData().sharesStorageWith(second.fractalData()));
            assertNotSame(first.validity(), second.validity());
        }
    }

    @Test
    void everyFrameOwnsStorageNoEarlierFrameReferences() {
        try (var storage = new SpareFrameStorage(tasks::add)) {
            List<RenderFrame> frames = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                RenderFrame frame = create(storage, job(40, 30, OrbitTrap.NONE, 100 + index));
                // Write to every frame, as a cancelled worker still might after its render ended.
                frame.fractalData().setValues(0, 7, 7.5, true, Double.NaN);
                frame.validity().markReady(new RenderRegion(0, 0, 40, 30));
                frames.add(frame);
                runTasks();
            }
            for (int later = 1; later < frames.size(); later++) {
                for (int earlier = 0; earlier < later; earlier++) {
                    assertFalse(frames.get(later).fractalData().sharesStorageWith(frames.get(earlier).fractalData()));
                    assertNotSame(frames.get(later).validity(), frames.get(earlier).validity());
                }
            }
            RenderFrame next = create(storage, job(40, 30, OrbitTrap.NONE, 100));
            assertEmpty(next);
        }
    }

    @Test
    void changingShapeAllocatesDirectlyAndDoesNotPrepareDroppedStorage() {
        try (var storage = new SpareFrameStorage(tasks::add)) {
            RenderJob small = job(40, 30, OrbitTrap.NONE, 100);
            create(storage, small);
            storage.prepareFor(small);
            runTasks();

            // A resize step: the spare of the old size is released, not reused at a new size.
            RenderFrame resized = create(storage, job(42, 30, OrbitTrap.NONE, 100));
            assertEmpty(resized);
            assertFalse(storage.hasSpareFor(small));
            assertTrue(tasks.isEmpty(), "A changing size must not allocate spares in the background");
            create(storage, job(44, 32, OrbitTrap.NONE, 100));
            assertTrue(tasks.isEmpty());

            RenderJob settled = job(44, 32, OrbitTrap.NONE, 100);
            create(storage, settled);
            assertEquals(1, tasks.size(), "Two frames of one size prepare a spare");
            runTasks();
            assertTrue(storage.hasSpareFor(settled));
        }
    }

    @Test
    void orbitTrapStorageIsPartOfTheShape() {
        try (var storage = new SpareFrameStorage(tasks::add)) {
            RenderJob plain = job(40, 30, OrbitTrap.NONE, 100);
            RenderJob trapped = job(40, 30, OrbitTrap.POINT, 100);
            storage.prepareFor(plain);
            runTasks();

            RenderFrame trappedFrame = create(storage, trapped);
            assertEmpty(trappedFrame);
            assertTrue(Double.isNaN(trappedFrame.fractalData().orbitTrapDistance(0)));
            trappedFrame.fractalData().setValues(0, 1, 1.0, true, 0.25);
            assertEquals(0.25, trappedFrame.fractalData().orbitTrapDistance(0));
            assertFalse(storage.hasSpareFor(plain));

            storage.prepareFor(trapped);
            runTasks();
            RenderFrame fromSpare = create(storage, trapped);
            assertEmpty(fromSpare);
            fromSpare.fractalData().setValues(0, 1, 1.0, true, 0.5);
            assertEquals(0.5, fromSpare.fractalData().orbitTrapDistance(0), "Spare storage keeps orbit-trap distances");
        }
    }

    @Test
    void preparationAllocatesOnlyTheLatestRequestedShape() {
        try (var storage = new SpareFrameStorage(tasks::add)) {
            RenderJob older = job(40, 30, OrbitTrap.NONE, 100);
            RenderJob latest = job(60, 40, OrbitTrap.NONE, 100);
            storage.prepareFor(older);
            storage.prepareFor(latest);
            assertEquals(1, tasks.size(), "Requests coalesce into one waiting preparation");
            runTasks();
            assertFalse(storage.hasSpareFor(older));
            assertTrue(storage.hasSpareFor(latest));

            storage.prepareFor(latest);
            assertTrue(tasks.isEmpty(), "A ready spare needs no new preparation");
        }
    }

    @Test
    void closeDropsTheSpareAndDiscardsPreparationInFlight() {
        var storage = new SpareFrameStorage(tasks::add);
        RenderJob job = job(40, 30, OrbitTrap.NONE, 100);
        storage.prepareFor(job);
        runTasks();
        storage.prepareFor(job(60, 40, OrbitTrap.NONE, 100));
        assertEquals(1, tasks.size());

        storage.close();
        storage.close();
        runTasks();
        assertFalse(storage.hasSpareFor(job));
        assertFalse(storage.hasSpareFor(job(60, 40, OrbitTrap.NONE, 100)));

        RenderFrame afterClose = create(storage, job);
        create(storage, job);
        assertEmpty(afterClose);
        assertTrue(tasks.isEmpty(), "A closed storage schedules no preparation");
    }

    @Test
    void ownedThreadPreparesTheSpareOffTheCallerThread() throws Exception {
        try (var storage = new SpareFrameStorage()) {
            RenderJob job = job(40, 30, OrbitTrap.NONE, 100);
            storage.prepareFor(job);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!storage.hasSpareFor(job)) {
                if (System.nanoTime() > deadline) fail("Spare was not prepared");
                Thread.onSpinWait();
            }
            assertEmpty(create(storage, job));
        }
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) task.run();
    }

    private static RenderFrame create(SpareFrameStorage storage, RenderJob job) {
        return storage.create(job, RenderGrid.from(job.viewport(), job.width(), job.height()));
    }

    static RenderJob job(int width, int height, OrbitTrap orbitTrap, int maxIterations) {
        return new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, orbitTrap),
                new Viewport("-0.75", "0.1", "1.2"), width, height, maxIterations);
    }

    /** Spare storage must be indistinguishable from a newly allocated frame. */
    static void assertEmpty(RenderFrame frame) {
        FractalData data = frame.fractalData();
        assertEquals(0, frame.validity().readyPixelCount());
        for (int index = 0; index < data.size(); index++) {
            assertEquals(0, data.iterations(index));
            assertEquals(0.0, data.smoothIterations(index));
            assertFalse(data.escaped(index));
            assertTrue(Double.isNaN(data.orbitTrapDistance(index)));
        }
    }
}
