package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.SceneGallery;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Places opened from the gallery export through the application's own PNG export path. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class SceneGalleryExportFxTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 100;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopToolkit() {
        Platform.exit();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void awaitFx(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!fx(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + description);
            Thread.sleep(25);
        }
    }

    private static FractalView fractalView(MainView main) {
        return ((StackPane) main.getCenter()).getChildren().stream()
                .filter(FractalView.class::isInstance)
                .map(FractalView.class::cast).findFirst().orElseThrow();
    }

    private static RenderModeIndicator badge(MainView main) {
        return fractalView(main).getChildrenUnmodifiable().stream()
                .filter(RenderModeIndicator.class::isInstance)
                .map(RenderModeIndicator.class::cast).findFirst().orElseThrow();
    }

    @Test
    void standardAndDeepZoomPlacesExportAsPng() throws Exception {
        var entries = SceneGallery.loadBundled().entries();
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage,
                new LastSessionStore(temporaryDirectory.resolve("last-session.json"))));
        try {
            fx(() -> {
                stage.setScene(new Scene(main, 640, 420));
                stage.show();
                return null;
            });
            awaitFx("initial frame", () -> fractalView(main).hasCompletedFrame());
            MainMenuBar menu = fx(() -> (MainMenuBar) main.getTop());
            for (String id : new String[] {"julia-douady-rabbit", "mandelbrot-misiurewicz-i"}) {
                var entry = entries.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
                fx(() -> {
                    menu.gallery().show();
                    return null;
                });
                // The platform presentation: the native panel on macOS, the JavaFX window elsewhere.
                awaitFx(id + " catalog", () -> menu.gallery().isShowing() && menu.gallery().hasCatalog());
                fx(() -> {
                    menu.gallery().open(id);
                    assertEquals(entry.scene().viewport(), fractalView(main).sceneSnapshot().viewport());
                    assertTrue(!menu.gallery().isShowing(), "Opening a place closes the gallery");
                    return null;
                });
                boolean deep = id.startsWith("mandelbrot-misiurewicz");
                awaitFx(id + " render", () -> badge(main).getText().contains("Complete")
                        && badge(main).getText().contains(deep ? "Deep Zoom" : "Standard"));

                Path path = temporaryDirectory.resolve(id + ".png");
                fx(() -> {
                    // The same snapshot and service File → Export PNG… uses after its dialogs.
                    main.startExport(fractalView(main).sceneSnapshot(), new RenderTarget(WIDTH, HEIGHT), path);
                    return null;
                });
                awaitFx(id + " export", () -> badge(main).getText().contains("PNG saved"));
                BufferedImage image = ImageIO.read(path.toFile());
                assertEquals(WIDTH, image.getWidth(), id);
                assertEquals(HEIGHT, image.getHeight(), id);
                var colors = new HashSet<Integer>();
                for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) colors.add(image.getRGB(x, y));
                assertTrue(colors.size() >= 32, id + " exported " + colors.size() + " colors");
            }
        } finally {
            fx(() -> {
                main.close();
                stage.close();
                return null;
            });
        }
    }
}
