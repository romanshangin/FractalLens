package com.shangin.fractal.export;

import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.JuliaDeepZoomRenderBackend;
import com.shangin.fractal.render.MandelbrotPerturbationRenderBackend;
import com.shangin.fractal.render.PrecisionSelectingRenderBackend;
import com.shangin.fractal.render.RefinedPixelSnapshot;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.SceneGallery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every gallery place renders in the antialiased interactive mode and exports as PNG. */
class GalleryRenderModesTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 100;

    @TempDir
    Path directory;

    private static int maxIterations(FractalScene scene) {
        return scene.iterations().maxIterations(
                scene.fractal().defaultViewport().scaleExact(), scene.viewport().scaleExact());
    }

    @Test
    void everyPlaceCompletesInteractiveAntialiasing() throws Exception {
        int deep = 0;
        try (var backend = new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(),
                new PrecisionSelectingRenderBackend(new MandelbrotPerturbationRenderBackend(),
                        new JuliaDeepZoomRenderBackend()));
             var antialiasing = new InteractiveAntialiasService()) {
            for (var entry : SceneGallery.loadBundled().entries()) {
                FractalScene scene = entry.scene();
                RenderJob job = new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(),
                        WIDTH, HEIGHT, maxIterations(scene));
                RenderFrame frame = backend.render(RenderFrame.create(job), () -> false, region -> {}, null);
                assertTrue(frame.isComplete(), entry.id());
                var coloring = scene.coloring().createStrategy(frame.samplePlane());
                // Deep places use the candidate-only refinement that keeps subpixel coordinates exact.
                boolean deepZoom = !scene.fractal().hasSufficientDirectPrecision(scene.viewport(), WIDTH, HEIGHT);
                if (deepZoom) deep++;
                var tiles = new AtomicInteger();
                var done = new CompletableFuture<Void>();
                var snapshot = RefinedPixelSnapshot.empty(WIDTH, HEIGHT);
                if (deepZoom) {
                    antialiasing.refineDeep(frame, coloring, snapshot, Runnable::run,
                            (region, colors) -> tiles.incrementAndGet(),
                            () -> done.complete(null), done::completeExceptionally);
                } else {
                    antialiasing.refine(frame, coloring, snapshot, Runnable::run,
                            (region, colors) -> tiles.incrementAndGet(),
                            () -> done.complete(null), done::completeExceptionally);
                }
                done.get(120, TimeUnit.SECONDS);
                assertTrue(tiles.get() > 0, entry.id() + " published no antialiased tiles");
            }
        }
        assertEquals(2, deep, "Both deep zoom places must take the deep antialiasing path");
    }

    @Test
    void everyPlaceExportsAsAnAdaptivelyAntialiasedPng() throws Exception {
        try (var service = new AdaptivePngExportService()) {
            for (var entry : SceneGallery.loadBundled().entries()) {
                Path path = directory.resolve(entry.id() + ".png");
                var exported = new CompletableFuture<Path>();
                service.export(entry.scene(), new RenderTarget(WIDTH, HEIGHT), path,
                        exported::complete, exported::completeExceptionally);
                exported.get(120, TimeUnit.SECONDS);
                BufferedImage image = ImageIO.read(path.toFile());
                assertEquals(WIDTH, image.getWidth(), entry.id());
                assertEquals(HEIGHT, image.getHeight(), entry.id());
                var colors = new HashSet<Integer>();
                for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) colors.add(image.getRGB(x, y));
                assertTrue(colors.size() >= 32, entry.id() + " exported " + colors.size() + " colors");
            }
        }
    }
}
