package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.PaletteRecolorBackend;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.scene.*;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stopping Animate Palette cancels palette work in flight. A palette chosen while the
 * animation ran is already in the scene, so the frame must end up shown in that palette.
 */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class PaletteAnimationStopFxTest {

    @BeforeAll
    static void start() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stop() { Platform.exit(); }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    void aPaletteChosenDuringAnimationIsShownAfterStoppingBeforeItIsPublished() throws Exception {
        var hold = new AtomicBoolean();
        var computed = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        FractalRenderController.RecolorOperation held = (service, frame, coloring, colors, backend) -> {
            var timing = service.recolorCachedInto(frame, coloring, colors, backend);
            if (hold.compareAndSet(true, false)) {
                computed.countDown();
                assertTrue(publish.await(15, TimeUnit.SECONDS));
            }
            return timing;
        };
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY));
        Object[] ui = fx(() -> {
            var view = new FractalView(scene, false, new FractalRenderService(), held);
            view.setWidePreviewSettings(WidePreviewSettings.DISABLED);
            // Animation frames are advanced by the test only.
            ((AnimationTimer) field(view, "colorCycleTimer")).stop();
            var stage = new Stage();
            stage.setScene(new Scene(view, 160, 100));
            stage.show();
            return new Object[]{view, stage};
        });
        var view = (FractalView) ui[0];
        try {
            var surface = (FractalSurface) fx(() -> field(view, "fractalSurface"));
            var controller = (FractalRenderController) fx(() -> field(view, "renderController"));
            awaitCompletedFrame(view, surface);
            Method advance = FractalView.class.getDeclaredMethod("advanceColorCycle", long.class);
            advance.setAccessible(true);

            fx(() -> {
                view.setColorCycling(true);
                advance.invoke(view, 1_000_000_000L);
                advance.invoke(view, 1_500_000_000L);
                return null;
            });
            awaitRecolorIdle(controller);
            assertNotEquals(scene.coloring().offset(), fx(() -> view.sceneSnapshot().coloring().offset()),
                    "The fixture must show an animated palette before the choice");

            hold.set(true);
            fx(() -> { view.setPalette(PalettePreset.FIRE); return null; });
            assertTrue(computed.await(15, TimeUnit.SECONDS), "The chosen palette must be computed");
            fx(() -> { view.setColorCycling(false); return null; });
            publish.countDown();
            awaitRecolorIdle(controller);

            ColoringSettings sceneColoring = fx(() -> view.sceneSnapshot().coloring());
            assertEquals(PalettePreset.FIRE, sceneColoring.palette(), "The scene must keep the chosen palette");
            assertEquals(sceneColoring, fx(() -> surface.completedRender().scene().coloring()),
                    "The shown frame must be in the scene's palette");
            RenderFrame frame = fx(() -> surface.completedRender().frame());
            // Base-only frames have no AA samples, so a fresh service recolors the base exactly.
            int[] expected = new int[frame.samplePlane().size()];
            new InteractiveAntialiasService().recolorCachedInto(frame,
                    sceneColoring.createStrategy(frame.samplePlane()), expected,
                    (PaletteRecolorBackend) field(controller, "paletteRecolorBackend"));
            assertArrayEquals(expected, fx(() -> pixels(visibleImage(surface))),
                    "The shown pixels must be colored with the scene's palette");
        } finally {
            publish.countDown();
            fx(() -> { view.close(); ((Stage) ui[1]).close(); return null; });
        }
    }

    private static void awaitCompletedFrame(FractalView view, FractalSurface surface) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> surface.completedRender() == null || (boolean) field(view, "rendering"))) {
            assertTrue(System.nanoTime() < deadline, "The initial frame must complete");
            Thread.sleep(10);
        }
    }

    /**
     * Waits until the recolor executor ran every palette task and the FX thread handled
     * what they posted, without queueing a task that could displace a pending one.
     */
    private static void awaitRecolorIdle(FractalRenderController controller) throws Exception {
        var executor = (ThreadPoolExecutor) field(controller, "recolorExecutor");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int idleChecks = 0;
        while (idleChecks < 2) {
            assertTrue(System.nanoTime() < deadline, "Palette work must settle");
            boolean idle = executor.getActiveCount() == 0 && executor.getQueue().isEmpty();
            fx(() -> null);
            idleChecks = idle ? idleChecks + 1 : 0;
            if (!idle) Thread.sleep(5);
        }
    }

    private static Image visibleImage(FractalSurface surface) {
        return ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
    }

    private static int[] pixels(Image image) {
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }
}
