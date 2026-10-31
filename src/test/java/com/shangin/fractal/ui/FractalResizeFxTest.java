package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.controller.RenderStatus;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.render.RenderPriority;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderBackend;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.TileTimingStats;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.control.DialogPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration checks: mvn -Dfractal.fx.tests=true -Dtest=FractalResizeFxTest test */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalResizeFxTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temporaryDirectory;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() { Platform.exit(); }

    private static FractalView fractalView(MainView main) {
        return ((StackPane) main.getCenter()).getChildren().stream()
                .filter(FractalView.class::isInstance)
                .map(FractalView.class::cast).findFirst().orElseThrow();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void renderBadgeShowsProgressAndLatestOutcomeWithoutHover() throws Exception {
        fx(() -> {
            RenderModeIndicator badge = new RenderModeIndicator();
            badge.setRenderStatus(new RenderStatus(RenderStatus.State.RENDERING, 0));
            badge.setProgress(0.42);
            assertTrue(badge.getText().contains("Rendering 42%"));
            badge.setProgress(0.99);
            assertTrue(badge.getText().contains("Rendering 99%"), "Progress covers the AA pass; there is no Refining state");
            badge.setRenderStatus(new RenderStatus(RenderStatus.State.FAILED, 0));
            assertTrue(badge.getText().contains("Failed"));
            badge.setRenderStatus(new RenderStatus(RenderStatus.State.COMPLETE, 15_000_000));
            assertTrue(badge.getText().contains("Complete 15 ms"));
            badge.setExportStatus("Export PNG 42%");
            assertTrue(badge.getText().contains("Complete 15 ms · Export PNG 42%"));
            badge.setRenderStatus(new RenderStatus(RenderStatus.State.RENDERING, 0));
            assertTrue(badge.getText().endsWith("Export PNG 42%"));
            badge.setExportStatus("");
            assertFalse(badge.getText().contains("Export PNG"));
            badge.setZoom(new java.math.BigDecimal("1234567"));
            badge.setIterations(1_234_567);
            // Hover shows only the values; mode descriptions belong in Help.
            assertEquals("Zoom: 1234567\nIterations: ≈1.23 × 10^6", badge.getTooltip().getText());
            // The tooltip zoom is the re-enterable Go to text; a zoom below 1 is not shown as 1.
            badge.setZoom(new java.math.BigDecimal("0.96504383940932216"));
            assertEquals("Zoom: 0.965043839409\nIterations: ≈1.23 × 10^6", badge.getTooltip().getText());
            badge.setZoom(new java.math.BigDecimal("2.718281828459045235360287E80"));
            assertTrue(badge.getTooltip().getText().startsWith("Zoom: 2.71828182846E+80\n"));
            badge.setProgress(0.0);
            badge.setRenderStatus(new RenderStatus(RenderStatus.State.RENDERING, 0));
            assertTrue(badge.getText().contains("Rendering 0%"));
            badge.setProgress(1.0);
            assertTrue(badge.getText().contains("Rendering 100%"));
            badge.setDeepZoom(true);
            assertTrue(badge.getText().contains("Deep Zoom · Rendering 100%"), badge.getText());
            assertFalse(badge.getTooltip().getText().contains("precision"), badge.getTooltip().getText());
            badge.setDeepZoom(false);
            assertTrue(badge.getText().contains("Standard · Rendering 100%"), badge.getText());
            assertFalse(badge.getText().contains("Refining"), badge.getText());
            // Progress must not resize the badge; only a different state may.
            double width = badge.getPrefWidth();
            for (double progress : new double[] {0.0, 0.05, 0.11, 0.42, 0.88}) {
                badge.setProgress(progress);
                assertEquals(width, badge.getPrefWidth(), badge.getText());
            }
            badge.setRenderStatus(new RenderStatus(RenderStatus.State.IDLE, 0));
            assertTrue(badge.getPrefWidth() < width, "Shorter states may shrink the badge");
            badge.close();
            badge.close();
            return null;
        });
    }

    @Test
    void renderBadgeTooltipTracksTheRenderedSceneAfterZoom() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE, false));
        Stage stage = fx(Stage::new);
        try {
            fx(() -> {
                stage.setScene(new Scene(view, 320, 240));
                stage.show();
                view.setOnZoomChanged(ignored -> {});
                return null;
            });
            FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
            awaitSurfaceComplete(surface);
            changeView(view, surface, () -> {
                view.zoom(true);
                view.zoom(true);
            });
            fx(() -> {
                RenderModeIndicator badge = (RenderModeIndicator) view.getChildrenUnmodifiable().get(1);
                String tooltip = badge.getTooltip().getText();
                int effectiveIterations = surface.completedRender().frame().request().maxIterations();
                assertTrue(tooltip.contains("Zoom: 1.5625\n"), tooltip);
                assertTrue(tooltip.contains("Iterations: " + RenderValueFormat.iterations(effectiveIterations)));
                assertFalse(tooltip.contains("Render time"));
                return null;
            });
        } finally {
            fx(() -> { view.close(); stage.close(); return null; });
        }
    }

    @Test
    void canvasKeyboardNavigationUsesCameraAndIgnoresModifiedKeys() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE, false));
        var stage = fx(javafx.stage.Stage::new);
        try {
            fx(() -> {
                stage.setScene(new javafx.scene.Scene(view, 320, 240));
                stage.show();
                return null;
            });
            FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
            awaitSurfaceComplete(surface);
            fx(() -> {
                RenderModeIndicator badge = (RenderModeIndicator) view.getChildrenUnmodifiable().get(1);
                int iterations = surface.completedRender().frame().request().maxIterations();
                assertEquals(2, view.getChildrenUnmodifiable().size(),
                        "The render badge must be the only canvas readout");
                assertTrue(badge.getTooltip().getText().contains("Zoom: 1"));
                assertTrue(badge.getTooltip().getText().contains("Iterations: " + iterations));
                Viewport initial = view.sceneSnapshot().viewport();
                view.fireEvent(key(KeyCode.PAGE_UP, false));
                Viewport zoomed = view.sceneSnapshot().viewport();
                assertNotEquals(initial, zoomed);
                view.fireEvent(key(KeyCode.RIGHT, false));
                assertNotEquals(zoomed, view.sceneSnapshot().viewport());
                Viewport panned = view.sceneSnapshot().viewport();
                view.fireEvent(key(KeyCode.LEFT, true));
                assertEquals(panned, view.sceneSnapshot().viewport());
                view.fireEvent(key(KeyCode.HOME, false));
                assertEquals(initial, view.sceneSnapshot().viewport());
                view.fireEvent(key(KeyCode.PAGE_UP, false));
                assertNotEquals(initial, view.sceneSnapshot().viewport());
                Viewport zoomedAgain = view.sceneSnapshot().viewport();
                view.fireEvent(key(KeyCode.PAGE_DOWN, false));
                assertNotEquals(zoomedAgain, view.sceneSnapshot().viewport());
                return null;
            });
        } finally {
            fx(() -> { view.close(); stage.close(); return null; });
        }
    }

    private static KeyEvent key(KeyCode code, boolean shortcut) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, shortcut, false, shortcut);
    }

    @Test
    void renderFailureShowsFailedStatusAndApplicationError() throws Exception {
        RenderBackend failingBackend = new RenderBackend() {
            @Override
            public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                                      Consumer<RenderRegion> regionCompleted,
                                      Consumer<TileTimingStats> timingCompleted) {
                throw new IllegalStateException("Controlled render failure");
            }

            @Override
            public void close() {}
        };
        var store = new com.shangin.fractal.app.LastSessionStore(
                temporaryDirectory.resolve("failure-session.json"));
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, store,
                new FractalRenderService(failingBackend)));
        try {
            fx(() -> {
                stage.setScene(new Scene(main, 320, 240));
                stage.show();
                return null;
            });
            awaitCondition(() -> fx(() -> alertShowing(stage, "Render failed")), 10);
            fx(() -> {
                assertError(stage, "Render failed", "The image could not be rendered: Controlled render failure");
                FractalView view = fractalView(main);
                RenderModeIndicator badge = view.getChildrenUnmodifiable().stream()
                        .filter(RenderModeIndicator.class::isInstance)
                        .map(RenderModeIndicator.class::cast).findFirst().orElseThrow();
                assertTrue(badge.getText().contains("Failed"));
                return null;
            });
        } finally {
            fx(() -> {
                Window.getWindows().stream()
                        .filter(window -> window instanceof Stage alert
                                && "Render failed".equals(alert.getTitle()))
                        .toList().forEach(Window::hide);
                main.close();
                stage.close();
                return null;
            });
        }
    }

    @Test
    void recolorFailureShowsApplicationErrorAfterCompletedRender() throws Exception {
        var store = new com.shangin.fractal.app.LastSessionStore(
                temporaryDirectory.resolve("recolor-failure-session.json"));
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, store, new FractalRenderService(),
                (service, frame, coloring, colors, backend) -> {
                    throw new IllegalStateException("Controlled recolor failure");
                }));
        FractalView view = fx(() -> fractalView(main));
        try {
            fx(() -> {
                stage.setScene(new Scene(main, 320, 240));
                stage.show();
                return null;
            });
            FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
            awaitSurfaceComplete(surface);
            fx(() -> {
                view.setPalette(PalettePreset.FIRE);
                return null;
            });
            awaitCondition(() -> fx(() -> alertShowing(stage, "Render failed")), 10);
            fx(() -> {
                assertError(stage, "Render failed", "The image could not be rendered: Controlled recolor failure");
                assertTrue(surface.hasCompletedFrame(),
                        "A recolor error must leave the completed frame available");
                return null;
            });
        } finally {
            fx(() -> {
                Window.getWindows().stream()
                        .filter(window -> window instanceof Stage alert
                                && "Render failed".equals(alert.getTitle()))
                        .toList().forEach(Window::hide);
                main.close();
                stage.close();
                return null;
            });
        }
    }

    @Test
    void exportCompletionClearsStatusAfterFourSeconds() throws Exception {
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, new com.shangin.fractal.app.LastSessionStore(
                temporaryDirectory.resolve("export-session.json"))));
        try {
            RenderModeIndicator badge = fx(() -> (RenderModeIndicator)
                    fractalView(main).getChildrenUnmodifiable().get(1));
            fx(() -> {
                stage.setScene(new Scene(main, 320, 240));
                stage.show();
                main.startExport(fractalView(main).sceneSnapshot(), new RenderTarget(17, 13),
                        temporaryDirectory.resolve("completed.png"));
                assertTrue(badge.getText().contains("Export PNG 0%"));
                return null;
            });
            awaitCondition(() -> fx(() -> badge.getText().contains("PNG saved")), 10);
            assertTrue(java.nio.file.Files.exists(temporaryDirectory.resolve("completed.png")));
            awaitCondition(() -> fx(() -> !badge.getText().contains("PNG saved")), 6);
        } finally {
            fx(() -> { main.close(); stage.close(); return null; });
        }
    }

    @Test
    void exportToReadOnlyFolderNamesChosenFileAndKeepsExistingImage() throws Exception {
        var folder = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("read-only"));
        var target = folder.resolve("existing.png");
        byte[] original = {1, 2, 3};
        java.nio.file.Files.write(target, original);
        var readOnly = java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x");
        try {
            java.nio.file.Files.setPosixFilePermissions(folder, readOnly);
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.abort("POSIX permissions are required to make the folder read-only");
        }
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, new com.shangin.fractal.app.LastSessionStore(
                temporaryDirectory.resolve("read-only-session.json"))));
        try {
            RenderModeIndicator badge = fx(() -> (RenderModeIndicator)
                    fractalView(main).getChildrenUnmodifiable().get(1));
            fx(() -> {
                stage.setScene(new Scene(main, 320, 240));
                stage.show();
                main.startExport(fractalView(main).sceneSnapshot(), new RenderTarget(17, 13), target);
                return null;
            });
            awaitCondition(() -> fx(() -> alertShowing(stage, "Export failed")), 10);
            fx(() -> {
                assertError(stage, "Export failed", "The PNG image could not be saved as \"existing.png\". "
                        + "FractalLens does not have permission to write to the folder \"" + folder
                        + "\". Choose another folder.");
                assertTrue(badge.getText().contains("PNG export failed."));
                return null;
            });
            assertArrayEquals(original, java.nio.file.Files.readAllBytes(target));
            try (var entries = java.nio.file.Files.list(folder)) {
                assertEquals(java.util.List.of(target), entries.toList());
            }
        } finally {
            java.nio.file.Files.setPosixFilePermissions(folder,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            fx(() -> { main.close(); stage.close(); return null; });
        }
    }

    @Test
    void closingDuringExportOffersReturnAndConfirmedCancellation() throws Exception {
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, new com.shangin.fractal.app.LastSessionStore(
                temporaryDirectory.resolve("close-session.json"))));
        var exportHeld = new CountDownLatch(1);
        var releaseExport = new CountDownLatch(1);
        // Hold the coordinator before submission so export cannot finish between decisions.
        var serviceField = MainView.class.getDeclaredField("exportService");
        serviceField.setAccessible(true);
        var service = serviceField.get(main);
        var coordinatorField = service.getClass().getDeclaredField("coordinator");
        coordinatorField.setAccessible(true);
        var exportCoordinator = (ExecutorService) coordinatorField.get(service);
        exportCoordinator.submit(() -> {
            exportHeld.countDown();
            try { releaseExport.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(exportHeld.await(10, TimeUnit.SECONDS));
            fx(() -> {
                stage.setScene(new Scene(main, 320, 240));
                stage.setOnHidden(event -> main.close());
                stage.show();
                main.startExport(fractalView(main).sceneSnapshot(), new RenderTarget(2000, 2000),
                        temporaryDirectory.resolve("closing.png"));
                var request = new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST);
                stage.fireEvent(request);
                assertTrue(request.isConsumed());
                assertTrue(stage.isShowing());
                return null;
            });
            awaitCondition(() -> fx(() -> alertShowing(stage, "Export in progress")), 10);
            fx(() -> { answerExportClose(stage, false); return null; });
            awaitCondition(() -> fx(() -> !alertShowing(stage, "Export in progress")), 10);
            fx(() -> {
                assertTrue(stage.isShowing(), "Returning from the decision must keep the owner open");
                var menus = (MainMenuBar) main.getTop();
                menus.getMenus().stream().filter(menu -> menu.getText().equals("File"))
                        .flatMap(menu -> menu.getItems().stream())
                        .filter(item -> "Close Window".equals(item.getText())).findFirst().orElseThrow().fire();
                assertTrue(stage.isShowing(), "Close must wait for the asynchronous decision");
                return null;
            });
            awaitCondition(() -> fx(() -> alertShowing(stage, "Export in progress")), 10);
            fx(() -> { answerExportClose(stage, true); return null; });
            awaitCondition(() -> fx(() -> !stage.isShowing()), 10);
            releaseExport.countDown();
            assertTrue(exportCoordinator.awaitTermination(10, TimeUnit.SECONDS),
                    "Confirmed closure must drain the cancelled export and release its executor");
            assertFalse(java.nio.file.Files.exists(temporaryDirectory.resolve("closing.png")),
                    "Confirmed closure must cancel the unfinished export");
        } finally {
            try { fx(() -> { main.close(); stage.close(); return null; }); }
            finally { releaseExport.countDown(); }
        }
    }

    private static void awaitCondition(Callable<Boolean> condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.call()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for application state");
            Thread.sleep(20);
        }
    }

    private static Stage fallbackAlert(String title) {
        return Window.getWindows().stream().filter(Window::isShowing)
                .filter(window -> window instanceof Stage candidate && title.equals(candidate.getTitle()))
                .map(Stage.class::cast).findFirst().orElse(null);
    }

    private static boolean alertShowing(Stage owner, String title) throws Exception {
        if (MacParameterSheet.isAvailable()) {
            var sheet = AppKitParameterProbe.activeSheet(owner);
            return sheet instanceof MacNoticeSheet && AppKitParameterProbe.showing(sheet);
        }
        return fallbackAlert(title) != null;
    }

    private static void assertError(Stage owner, String title, String detail) throws Exception {
        if (MacParameterSheet.isAvailable()) {
            var sheet = AppKitParameterProbe.activeSheet(owner);
            assertInstanceOf(MacNoticeSheet.class, sheet);
            assertEquals(title, AppKitParameterProbe.alertText(sheet, "messageText"));
            assertEquals(detail, AppKitParameterProbe.alertText(sheet, "informativeText"));
            AppKitParameterProbe.clickAlert(sheet, false);
        } else {
            var alert = fallbackAlert(title);
            assertNotNull(alert);
            DialogPane pane = (DialogPane) alert.getScene().getRoot();
            assertEquals(title, pane.getHeaderText());
            assertEquals(detail, pane.getContentText());
            ((javafx.scene.control.Button) pane.lookupButton(javafx.scene.control.ButtonType.OK)).fire();
        }
    }

    private static void answerExportClose(Stage owner, boolean accept) throws Exception {
        if (MacParameterSheet.isAvailable()) {
            var sheet = AppKitParameterProbe.activeSheet(owner);
            assertEquals("Close FractalLens?", AppKitParameterProbe.alertText(sheet, "messageText"));
            AppKitParameterProbe.clickAlert(sheet, accept);
            return;
        }
        Stage dialog = fallbackAlert("Export in progress");
        assertNotNull(dialog);
        DialogPane pane = (DialogPane) dialog.getScene().getRoot();
        var stay = pane.getButtonTypes().stream().filter(button -> button.getText().equals("No, Return"))
                .findFirst().orElseThrow();
        assertTrue(((javafx.scene.control.Button) pane.lookupButton(stay)).isDefaultButton());
        String answer = accept ? "Yes, Close" : "No, Return";
        pane.getButtonTypes().stream().filter(button -> button.getText().equals(answer))
                .findFirst().map(pane::lookupButton).map(javafx.scene.control.Button.class::cast)
                .orElseThrow().fire();
    }

    @Test
    void mainViewRestoresTheSavedSceneBeforeItsFirstRender() throws Exception {
        var restored = new FractalScene(
                FractalPreset.JULIA,
                new Viewport(
                        "-0.743643887037151000000000000000000000000000000000000000000001",
                        "0.131825904205330000000000000000000000000000000000000000000007",
                        "1.2E-75"),
                new com.shangin.fractal.scene.IterationSettings(420, 70),
                new com.shangin.fractal.scene.ColoringSettings(PalettePreset.FIRE),
                new AntialiasSettings(InteractiveRenderMode.FAST));
        var store = new com.shangin.fractal.app.LastSessionStore(
                temporaryDirectory.resolve("last-session.json"));
        store.save(restored);
        var stage = fx(javafx.stage.Stage::new);
        var main = fx(() -> new MainView(stage, store));
        try {
            var view = fx(() -> fractalView(main));
            assertEquals(restored, fx(view::sceneSnapshot));
            assertEquals("Julia — FractalLens", fx(stage::getTitle));
        } finally {
            fx(() -> {
                main.close();
                stage.close();
                return null;
            });
        }
    }

    @Test
    void mainViewFitsTheDefaultFallbackSceneToTheInitialAspectRatio() throws Exception {
        var store = new com.shangin.fractal.app.LastSessionStore(
                temporaryDirectory.resolve("missing-session.json"));
        var stage = fx(javafx.stage.Stage::new);
        var main = fx(() -> new MainView(stage, store));
        var view = fx(() -> fractalView(main));
        var surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> {
                view.resize(600, 900);
                view.layout();
            });

            Viewport expected = new FractalCamera(FractalPreset.MANDELBROT)
                    .defaultViewport(600, 900);
            assertEquals(expected, fx(view::sceneSnapshot).viewport());
        } finally {
            fx(() -> {
                main.close();
                stage.close();
                return null;
            });
        }
    }

    @Test
    void goToCoordinatesAppliesOnlyEditedFieldsAgainstTheLiveViewAndUndoes() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE, true));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        var zoom = new java.math.BigDecimal("1E20");
        try {
            changeView(view, surface, () -> {
                view.resize(320, 240);
                view.layout();
            });
            Viewport home = fx(view::sceneSnapshot).viewport();
            CoordinateEditor editor = fx(view::coordinateEditor);
            CoordinateText shown = fx(view::coordinateText);
            assertEquals(new CoordinateText("-0.75", "0", "1"), shown);

            int[] renders = {0};
            fx(() -> {
                view.setOnRenderingChanged(active -> { if (active) renders[0]++; });
                // Unchanged drafts are a full no-op; invalid ones change nothing either.
                assertEquals(0, editor.submit(shown, " -0.75 ", "0", "1"));
                assertEquals(1, editor.submit(shown, "x", "0", "1e20"));
                assertEquals(3, editor.submit(shown, "0.25", "0", "0.5"));
                assertSame(home, view.sceneSnapshot().viewport());
                assertFalse(view.canUndo());
                return null;
            });
            pauseFx(300);
            assertEquals(0, fx(() -> renders[0]), "A no-op or failed Go to must not start a render");

            // Only the zoom changed: the exact live center stays.
            Viewport deep = new FractalCamera(FractalPreset.MANDELBROT).destination(home.center(), zoom, 320, 240);
            changeView(view, surface, () -> assertEquals(0, editor.submit(shown, "-0.75", "0", "1e20")), 30);
            fx(() -> {
                assertEquals(deep, view.sceneSnapshot().viewport());
                assertEquals(deep, surface.completedRender().frame().job().viewport());
                return null;
            });

            // Only the real coordinate changed: imaginary and scale stay exact.
            CoordinateText deepShown = fx(view::coordinateText);
            assertEquals("1E+20", deepShown.zoom());
            String real = "-0.743643887037151000000000000000001";
            changeView(view, surface, () -> assertEquals(0,
                    editor.submit(deepShown, real, deepShown.imaginary(), deepShown.zoom())), 30);
            fx(() -> {
                Viewport moved = view.sceneSnapshot().viewport();
                assertEquals(new java.math.BigDecimal(real), moved.center().real());
                assertEquals(deep.center().imaginary(), moved.center().imaginary());
                assertEquals(deep.scaleExact(), moved.scaleExact());
                return null;
            });

            changeView(view, surface, view::undoScene, 30);
            assertEquals(deep, fx(view::sceneSnapshot).viewport());
            changeView(view, surface, view::undoScene);
            assertEquals(home, fx(view::sceneSnapshot).viewport());

            changeView(view, surface, () -> view.setFractal(FractalPreset.TRICORN));
            // Away from the origin, where doubles cannot resolve a 1e20 zoom.
            CoordinateText tricorn = fx(view::coordinateText);
            assertEquals(3, (int) fx(() -> editor.invalidField(tricorn, "-0.75", tricorn.imaginary(), "1e20")));
            assertEquals(0, (int) fx(() -> editor.invalidField(tricorn, "-0.75", tricorn.imaginary(), "10")));
            assertEquals(3, (int) fx(() -> editor.submit(tricorn, "-0.75", tricorn.imaginary(), "1e20")));
            assertEquals(FractalPreset.TRICORN, fx(view::sceneSnapshot).fractal());
            assertEquals(new CoordinateText("0", "0", "1"), fx(view::coordinateText));
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void goToCoordinatesUsesLiveValuesAfterResizeAndKeepsAZoomBelowOneUntouched() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE, true));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> {
                view.resize(320, 240);
                view.layout();
            });
            Viewport home = fx(view::sceneSnapshot).viewport();
            // A restored or resized view can be wider than the initial view.
            Viewport wide = new Viewport(new com.shangin.fractal.math.PreciseComplex("-0.5", "0.25"),
                    new java.math.BigDecimal("5"));
            changeView(view, surface, () -> view.goTo(wide));
            CoordinateEditor editor = fx(view::coordinateEditor);
            CoordinateText shown = fx(view::coordinateText);
            assertTrue(new java.math.BigDecimal(shown.zoom()).compareTo(java.math.BigDecimal.ONE) < 0, shown::zoom);
            int[] renders = {0};
            fx(() -> {
                view.setOnRenderingChanged(active -> { if (active) renders[0]++; });
                assertEquals(0, editor.submit(shown, shown.real(), shown.imaginary(), shown.zoom()));
                assertEquals(wide, view.sceneSnapshot().viewport());
                return null;
            });
            pauseFx(300);
            assertEquals(0, fx(() -> renders[0]));

            // The window changes while the editor is open; Go uses the live scale.
            changeView(view, surface, () -> {
                view.resize(400, 300);
                view.layout();
            });
            Viewport resized = fx(view::sceneSnapshot).viewport();
            assertNotEquals(wide.scaleExact(), resized.scaleExact());
            changeView(view, surface, () -> assertEquals(0,
                    editor.submit(shown, "0.1", shown.imaginary(), shown.zoom())));
            fx(() -> {
                Viewport moved = view.sceneSnapshot().viewport();
                assertEquals(new com.shangin.fractal.math.PreciseComplex(
                        new java.math.BigDecimal("0.1"), resized.center().imaginary()), moved.center());
                assertEquals(resized.scaleExact(), moved.scaleExact());
                return null;
            });

            // The unchanged Go recorded no undo entry.
            changeView(view, surface, view::undoScene);
            changeView(view, surface, view::undoScene);
            Viewport undone = fx(view::sceneSnapshot).viewport();
            assertEquals(home.center(), undone.center(), undone::toString);
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void copiedCoordinatesBeforeTheFirstRenderUseTheWindowRenderGrid() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE, true));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            fx(() -> {
                view.resize(1000, 700);
                assertTrue(surface.renderWidth() < 2, "No render grid exists yet");
                Viewport viewport = view.sceneSnapshot().viewport();
                var zoom = new FractalCamera(FractalPreset.MANDELBROT).defaultViewport(1000, 700).scaleExact()
                        .divide(viewport.scaleExact(), viewport.mathContext());
                assertEquals(CoordinateText.of(viewport, surface.renderWidthFor(1000), surface.renderHeightFor(700),
                        zoom), view.coordinateText());
                return null;
            });
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void deepJuliaCompletesAndRevealsTheCanvasAtWindowResolution() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.JULIA, PalettePreset.ICE, true));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        Viewport destination = com.shangin.fractal.render.ReportedJuliaFixture.viewport();
        try {
            changeView(view, surface, () -> {
                view.resize(1200, 780);
                view.layout();
                view.goTo(destination);
                assertFalse(surface.isVisible());
            }, 30);
            fx(() -> {
                assertTrue(surface.hasCompletedFrame());
                assertTrue(surface.isVisible(), "Completed Julia must be visible after the loading layer closes");
                assertEquals(2, view.getChildrenUnmodifiable().size());
                assertEquals(destination, surface.completedRender().frame().job().viewport());
                assertFalse(FractalPreset.JULIA.hasSufficientDirectPrecision(destination, 1200, 780));
                return null;
            });
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void loadingScreenWaitsForFullRenderAndReturnsOnlyForSceneChanges(boolean macStartup) throws Exception {
        FractalView view = fx(() -> new FractalView(
                FractalPreset.MANDELBROT, PalettePreset.ICE, macStartup));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            fx(() -> {
                assertEquals(!macStartup, surface.isVisible());
                assertTrue(surface.isManaged(), "Hidden surface must still receive layout");
                assertEquals(macStartup ? 3 : 2, view.getChildrenUnmodifiable().size());
                RenderModeIndicator badge = (RenderModeIndicator) view.getChildrenUnmodifiable().get(1);
                assertTrue(badge.isVisible(), "The status must be visible while the loading screen is shown");
                view.getChildrenUnmodifiable().stream().filter(LoadingScreen.class::isInstance)
                        .forEach(loading -> assertTrue(badge.getViewOrder() < loading.getViewOrder(),
                                "The status must be drawn above the loading screen"));
                return null;
            });
            changeView(view, surface, () -> {
                view.resize(120, 90);
                view.layout();
                assertEquals(!macStartup, surface.isVisible(), "Layout must not reveal partial pixels");
                // Supersede the initial request before it can finish.
                view.resize(160, 100);
                view.layout();
                assertEquals(!macStartup, surface.isVisible(), "Resize cancellation must not reveal the canvas");
            });
            fx(() -> {
                assertTrue(surface.isVisible());
                assertEquals(2, view.getChildrenUnmodifiable().size());
                assertTrue(surface.hasCompletedFrame());
                return null;
            });
            changeView(view, surface, () -> {
                view.resize(180, 110);
                view.layout();
                assertTrue(surface.isVisible(), "Later renders must remain progressive");
                assertEquals(2, view.getChildrenUnmodifiable().size());
            });
            for (boolean reset : new boolean[] {false, true}) {
                changeView(view, surface, () -> {
                    if (reset) view.resetView();
                    else view.setFractal(FractalPreset.JULIA);
                    assertEquals(!macStartup, surface.isVisible(),
                            "Scene changes must hide the old frame until rendering finishes");
                    assertEquals(macStartup ? 3 : 2, view.getChildrenUnmodifiable().size());
                });
                fx(() -> {
                    assertTrue(surface.isVisible());
                    assertEquals(2, view.getChildrenUnmodifiable().size());
                    assertTrue(surface.hasCompletedFrame());
                    return null;
                });
            }
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void wheelAndPinchRecalculateIterationBudget(boolean pinch) throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(100, 80); view.layout(); });
            int before = fx(() -> surface.completedRender().frame().job().maxIterations());
            changeView(view, surface, () -> {
                if (pinch) {
                    for (var type : java.util.List.of(javafx.scene.input.ZoomEvent.ZOOM_STARTED,
                            javafx.scene.input.ZoomEvent.ZOOM, javafx.scene.input.ZoomEvent.ZOOM_FINISHED)) {
                        view.fireEvent(new javafx.scene.input.ZoomEvent(type, 50, 40, 50, 40,
                                false, false, false, false, false, false, 2, 2, null));
                    }
                } else {
                    view.fireEvent(new javafx.scene.input.ScrollEvent(
                            javafx.scene.input.ScrollEvent.SCROLL, 50, 40, 50, 40,
                            false, false, false, false, false, false, 0, 40, 0, 40,
                            javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                            javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                }
            });
            var completed = fx(surface::completedRender);
            int expected = completed.scene().iterations().maxIterations(
                    new FractalCamera(FractalPreset.MANDELBROT).defaultViewport(100, 80).scaleExact(),
                    completed.scene().viewport().scaleExact());
            assertTrue(expected > before);
            assertEquals(expected, completed.frame().job().maxIterations(),
                    "Zoom must increase the budget instead of retaining the initial 300 iterations");
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void refinedResizeKeepsCalculatedBaseEdgesVisibleDuringAntialiasing() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        fx(() -> {
            var source = com.shangin.fractal.render.RenderFrame.create(new com.shangin.fractal.render.RenderRequest(
                    new com.shangin.fractal.render.FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                    FractalPreset.MANDELBROT.defaultViewport(), 4, 4, 20));
            var target = com.shangin.fractal.render.RenderFrame.create(new com.shangin.fractal.render.RenderRequest(
                    new com.shangin.fractal.render.FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                    FractalPreset.MANDELBROT.defaultViewport(), 8, 8, 20));
            var wholeFrame = new com.shangin.fractal.render.RenderRegion(0, 0, 8, 8);
            target.validity().markReady(wholeFrame);
            surface.resize(8, 8);
            surface.resizeBuffer(8, 8);
            surface.beginProgressiveRender(target, source, InteractiveRenderMode.REFINED);
            surface.displayProgress(new com.shangin.fractal.render.RenderProgressBatch(
                            target, java.util.List.of(wholeFrame)),
                    new com.shangin.fractal.coloring.SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    InteractiveRenderMode.REFINED);
            var progressive = (ImageView) surface.getChildrenUnmodifiable().get(2);
            assertNotNull(progressive.getImage(), "Resize edges must appear during the base pass");
            assertEquals(255, progressive.getImage().getPixelReader().getArgb(0, 0) >>> 24);
            surface.beginRefinedRender(target);
            assertEquals(255, progressive.getImage().getPixelReader().getArgb(0, 0) >>> 24,
                    "Starting AA must not clear already calculated resize edges");
            return null;
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "fractal.fx.fullscreen", matches = "true")
    void reportedTrackpadZoomFullscreenCase() throws Exception {
        var values = java.nio.file.Files.readAllLines(java.nio.file.Path.of(
                "src/test/resources/com/shangin/fractal/ui/trackpad-fullscreen-case.txt"));
        var real = new java.math.BigDecimal(values.get(0).split(": ", 2)[1]);
        var imaginary = new java.math.BigDecimal(values.get(1).split(": ", 2)[1]);
        var zoom = new java.math.BigDecimal(values.get(2).split(": ", 2)[1]);
        var stage = fx(javafx.stage.Stage::new);
        var main = fx(() -> new MainView(stage));
        var view = fx(() -> fractalView(main));
        var surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            fx(() -> {
                var bounds = javafx.stage.Screen.getPrimary().getBounds();
                stage.setScene(new javafx.scene.Scene(main,
                        Math.floor(bounds.getWidth() * .75), Math.floor(bounds.getHeight() * .75)));
                stage.setFullScreenExitHint("");
                stage.show();
                stage.centerOnScreen();
                return null;
            });
            awaitSurfaceComplete(surface);
            System.out.println("Reported case: initial window ready");
            int zoomStages = 8;
            double stageFactor = Math.pow(zoom.doubleValue(), 1.0 / zoomStages);
            for (int zoomStage = 0; zoomStage < zoomStages; zoomStage++) {
                var beforeZoom = fx(surface::completedRender);
                int currentStage = zoomStage;
                fx(() -> {
                    double x = (view.getWidth() - 1) / 2;
                    double y = (view.getHeight() - 1) / 2;
                    view.fireEvent(new javafx.scene.input.ZoomEvent(
                            javafx.scene.input.ZoomEvent.ZOOM_STARTED, x, y, 100, 100,
                            false, false, false, false, false, false, 1, 1, null));
                    view.fireEvent(new javafx.scene.input.ZoomEvent(
                            javafx.scene.input.ZoomEvent.ZOOM, x, y, 100, 100,
                            false, false, false, false, false, false, stageFactor, stageFactor, null));
                    if (currentStage == 0) view.setCenter(real, imaginary);
                    view.fireEvent(new javafx.scene.input.ZoomEvent(
                            javafx.scene.input.ZoomEvent.ZOOM_FINISHED, x, y, 100, 100,
                            false, false, false, false, false, false, 1, stageFactor, null));
                    return null;
                });
                awaitNewSurfaceFrame(surface, beforeZoom);
                pauseFx(1000);
                System.out.println("Reported case: zoom stage " + (zoomStage + 1) + " of " + zoomStages + " finished");
            }
            pauseFx(5000);
            System.out.println("Reported case: zoom render finished and remained idle for 5 seconds; entering fullscreen");
            fx(() -> { stage.setFullScreen(true); return null; });
            pauseFx(8000);
            fx(() -> {
                saveSurface(surface, "target/reported-trackpad-fullscreen.png");
                var frame = surface.completedRender();
                System.out.println("Reported case: buffer=" + surface.renderWidth() + "x" + surface.renderHeight()
                        + ", displayed=" + frame.frame().request().width() + "x" + frame.frame().request().height());
                for (int layer = 1; layer < 3; layer++) {
                    assertNull(((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage(),
                            "Fullscreen must finish without further pointer input");
                }
                assertEquals(surface.renderWidth(), frame.frame().request().width());
                assertEquals(surface.renderHeight(), frame.frame().request().height());
                return null;
            });
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
        } finally {
            fx(() -> { main.close(); stage.close(); return null; });
        }
    }

    private static void awaitNewSurfaceFrame(FractalSurface surface,
                                                   com.shangin.fractal.render.CompletedRender previous) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        var timer = fx(() -> {
            var checker = new javafx.animation.AnimationTimer() {
                @Override public void handle(long now) {
                    var frame = surface.completedRender();
                    if (frame == null || frame == previous) return;
                    for (int layer = 1; layer < 3; layer++) {
                        if (((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage() != null) return;
                    }
                    stop();
                    completed.countDown();
                }
            };
            checker.start();
            return checker;
        });
        try {
            assertTrue(completed.await(30, TimeUnit.SECONDS), "The zoomed window frame must finish first");
        } finally {
            fx(() -> { timer.stop(); return null; });
        }
    }

    private static void pauseFx(int milliseconds) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        fx(() -> {
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(milliseconds));
            pause.setOnFinished(event -> done.countDown());
            pause.play();
            return null;
        });
        assertTrue(done.await(milliseconds + 5000L, TimeUnit.MILLISECONDS));
    }

    private static void saveSurface(FractalSurface surface, String path) throws Exception {
        var image = surface.snapshot(null, null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        var output = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, w, h, pixels, 0, w);
        javax.imageio.ImageIO.write(output, "png", new java.io.File(path));
    }

    @Test
    void contextMenuDismissesWithoutSwallowingCanvasInput() throws Exception {
        var stage = fx(javafx.stage.Stage::new);
        var canvas = fx(javafx.scene.layout.Pane::new);
        var copied = new java.util.concurrent.atomic.AtomicInteger();
        var menu = fx(() -> new FractalContextMenu(canvas, copied::incrementAndGet));
        try {
            fx(() -> {
                stage.setScene(new javafx.scene.Scene(canvas, 360, 180));
                stage.show();
                return null;
            });
            fx(() -> { menu.show(canvas, stage.getX() + 40, stage.getY() + 60); return null; });
            fx(() -> {
                assertTrue(menu.isShowing(), "Opening must leave the menu usable");
                var image = menu.getScene().snapshot(null);
                int w = (int) image.getWidth(), h = (int) image.getHeight();
                int[] pixels = new int[w * h];
                image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
                var output = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                output.setRGB(0, 0, w, h, pixels, 0, w);
                javax.imageio.ImageIO.write(output, "png", new java.io.File("target/context-menu-qa.png"));
                var clicks = new java.util.concurrent.atomic.AtomicInteger();
                canvas.setOnMousePressed(event -> clicks.incrementAndGet());
                canvas.fireEvent(new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_PRESSED, 10, 10, 10, 10,
                        javafx.scene.input.MouseButton.PRIMARY, 1,
                        false, false, false, false, true, false, false, false, false, true, null));
                assertFalse(menu.isShowing());
                assertEquals(1, clicks.get(), "Dismissal must not require a second click to start dragging");
                menu.show(canvas, stage.getX() + 40, stage.getY() + 60);
                canvas.fireEvent(new javafx.scene.input.ScrollEvent(
                        javafx.scene.input.ScrollEvent.SCROLL, 10, 10, 10, 10,
                        false, false, false, false, false, false, 0, 1, 0, 1,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                assertFalse(menu.isShowing(), "Scrolling must dismiss the popup");
                menu.show(canvas, stage.getX() + 40, stage.getY() + 60);
                menu.getItems().getFirst().fire();
                assertEquals(1, copied.get());
                assertFalse(menu.isShowing());
                menu.show(canvas, stage.getX() + 40, stage.getY() + 60);
                stage.setWidth(stage.getWidth() + 20);
                assertFalse(menu.isShowing(), "Window changes must dismiss the popup");
                return null;
            });
        } finally {
            fx(() -> { menu.close(); stage.close(); return null; });
        }
    }

    @ParameterizedTest
    @EnumSource(InteractiveRenderMode.class)
    void resizePreservesCenterColorsAndCropCompletesSynchronously(InteractiveRenderMode mode) throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        try {
            camera.resize(80, 60);
            camera.zoomBy(40, 30, 0.25, 80, 60, 80, 60);
            Viewport original = camera.viewport();
            render(surface, controller, camera, 80, 60, false, mode);
            int[] windowPixels = pixels(surface);
            int iterations = surface.completedRender().frame().request().maxIterations();
            render(surface, controller, camera, 120, 100, true, mode);
            int[] fullscreenPixels = pixels(surface);
            assertEquals(iterations, surface.completedRender().frame().request().maxIterations());
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 80; x++) {
                    assertEquals(windowPixels[y * 80 + x], fullscreenPixels[(y + 20) * 120 + x + 20],
                            "Resizing must preserve already displayed colors at " + x + "," + y);
                }
            }
            fx(() -> {
                controller.cancelCurrent();
                surface.resize(80, 60);
                surface.resizeBuffer(80, 60);
                camera.resize(80, 60, 100, 60);
                controller.render(scene(camera).withAntialiasing(new AntialiasSettings(mode)),
                        new RenderTarget(80, 60, RenderPriority.center()),
                        camera.defaultViewport(80, 60), true);
                // No asynchronous render or AA completion is needed for a crop.
                assertEquals(80, surface.completedRender().frame().request().width());
                assertEquals(60, surface.renderHeight());
                assertEquals(80, surface.renderWidth());
                return null;
            });
            assertEquals(original.center(), camera.viewport().center());
            assertEquals(original.scale(), camera.viewport().scale(), 1e-15);
            assertArrayEquals(windowPixels, pixels(surface));
            render(surface, controller, camera, 120, 100, true, mode);
            assertArrayEquals(fullscreenPixels, pixels(surface), "Second expansion must restore all pixels");
            render(surface, controller, camera, 80, 60, true, mode);
            assertArrayEquals(windowPixels, pixels(surface), "Second crop must preserve the center");
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void standardModeSwitchesPublishOnlyTheSelectedPresentation() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        var statuses = new CopyOnWriteArrayList<RenderStatus.State>();
        try {
            fx(() -> {
                controller.setOnRenderStatusChanged(status -> statuses.add(status.state()));
                return null;
            });
            render(surface, controller, camera, 80, 60, false, InteractiveRenderMode.BASE_ONLY);
            int[] base = basePixels(surface);
            assertArrayEquals(base, pixels(surface), "Base only must publish uncorrected base colors");
            assertEquals(RenderStatus.State.COMPLETE, statuses.getLast());
            RenderFrame frame = fx(() -> surface.completedRender().frame());

            render(surface, controller, camera, 80, 60, false, InteractiveRenderMode.FAST);
            int[] antialiased = pixels(surface);
            assertFalse(java.util.Arrays.equals(base, antialiased), "The fixture must contain refined edges");
            assertSame(frame, fx(() -> surface.completedRender().frame()),
                    "Antialiasing must refine the visible base frame without recalculating it");

            int cachedSamples = cachedAntialiasSamples(controller);
            assertTrue(cachedSamples > 0);

            statuses.clear();
            render(surface, controller, camera, 80, 60, false, InteractiveRenderMode.BASE_ONLY);
            assertArrayEquals(base, pixels(surface), "Base only must not keep reused antialiased pixels");
            assertEquals(java.util.List.of(RenderStatus.State.RENDERING, RenderStatus.State.COMPLETE), statuses);
            assertSame(frame, fx(() -> surface.completedRender().frame()));
            assertEquals(cachedSamples, cachedAntialiasSamples(controller),
                    "Base only must keep finished AA samples for a later antialiasing mode");

            CountDownLatch recolored = new CountDownLatch(1);
            fx(() -> {
                controller.recolor(surface.completedRender().scene().coloring(), recolored::countDown);
                return null;
            });
            assertTrue(recolored.await(15, TimeUnit.SECONDS));
            int[] baseRecolor = fx(() -> {
                var completed = surface.completedRender();
                try (var withoutAntialiasing = new com.shangin.fractal.export.InteractiveAntialiasService()) {
                    return withoutAntialiasing.recolorCached(completed.frame(),
                            completed.scene().coloring().createStrategy(completed.frame().samplePlane()));
                }
            });
            assertArrayEquals(baseRecolor, pixels(surface), "Recoloring must not restore cached antialiased samples");

            render(surface, controller, camera, 80, 60, false, InteractiveRenderMode.REFINED);
            assertArrayEquals(antialiased, pixels(surface), "Antialiased display must refine the reused base");
            assertSame(frame, fx(() -> surface.completedRender().frame()));
            assertEquals(cachedSamples, cachedAntialiasSamples(controller), "No AA sample may be recalculated");
            render(surface, controller, camera, 80, 60, false, InteractiveRenderMode.FAST);
            assertArrayEquals(antialiased, pixels(surface));
            assertSame(frame, fx(() -> surface.completedRender().frame()));
            assertEquals(InteractiveRenderMode.FAST,
                    fx(() -> surface.completedRender().scene().antialiasing().renderMode()));
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @ParameterizedTest
    @EnumSource(InteractiveRenderMode.class)
    void renderProgressCoversTheWholeFrameIncludingAntialiasing(InteractiveRenderMode mode) throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        // Progress and status callbacks both run on the FX thread, so one list keeps their order.
        var events = new CopyOnWriteArrayList<Object>();
        try {
            fx(() -> {
                controller.setOnRenderProgressChanged(events::add);
                controller.setOnRenderStatusChanged(status -> events.add(status.state()));
                return null;
            });
            render(surface, controller, camera, 80, 60, false, mode);
            int complete = events.indexOf(RenderStatus.State.COMPLETE);
            assertTrue(complete > 0, events.toString());
            java.util.List<Double> progress = events.subList(0, complete).stream()
                    .filter(Double.class::isInstance).map(Double.class::cast).toList();
            for (int index = 1; index < progress.size(); index++) {
                assertTrue(progress.get(index) >= progress.get(index - 1), "Progress must not go back: " + progress);
            }
            assertEquals(1.0, progress.getLast(), "The frame is complete only at 100%: " + progress);
            if (mode.antialiases()) {
                // The base render ends at half; the AA pass reports the rest.
                assertTrue(progress.contains(0.5), progress.toString());
                assertTrue(progress.stream().anyMatch(value -> value > 0.5 && value < 1.0), progress.toString());
                assertEquals(progress.size() - 1, progress.indexOf(1.0),
                        "100% must wait for the last AA tile: " + progress);
            }
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @ParameterizedTest
    @EnumSource(value = InteractiveRenderMode.class, names = {"FAST", "REFINED"})
    void deepPanProgressStartsWithReusedSamplesAndAntialiasedPixels(InteractiveRenderMode mode) throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalScene deep = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withViewport(new Viewport("-0.8267486182939549503119853330756",
                        "0.2150828768422976562284460839629", "1e-16"));
        var progress = new CopyOnWriteArrayList<Double>();
        try {
            fx(() -> {
                controller.setOnRenderProgressChanged(progress::add);
                surface.resize(80, 60);
                surface.resizeBuffer(80, 60);
                return null;
            });
            render(surface, controller, deep, mode);
            assertTrue(fx(() -> surface.refinedPixelCount(surface.completedRender().frame())) == 80 * 60,
                    "The source frame must be fully antialiased");

            // Pan by half the width: half of the samples and half of the AA pixels are reused.
            var unit = deep.viewport().imaginaryUnitsPerPixelExact(60);
            FractalScene panned = deep.withViewport(deep.viewport().pan(
                    unit.multiply(java.math.BigDecimal.valueOf(40)), java.math.BigDecimal.ZERO));
            progress.clear();
            render(surface, controller, panned, mode);
            double first = progress.stream().filter(value -> value > 0.0).findFirst().orElseThrow();
            assertEquals(0.5, first, 0.05, "Reused work must be reported before new work: " + progress);
            assertEquals(1.0, progress.getLast());
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"smooth", "histogram", "orbit-trap"})
    void baseOnlyKeepsFinishedAntialiasingForEveryColoring(String coloringName) throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        var palette = new com.shangin.fractal.scene.ColoringSettings(PalettePreset.ICE);
        var coloring = new com.shangin.fractal.scene.ColoringSettings(palette.palette(), palette.paletteStops(),
                palette.colorScale(), palette.offset(), coloringName.equals("histogram"),
                coloringName.equals("orbit-trap") ? com.shangin.fractal.coloring.OrbitTrap.CROSS
                        : com.shangin.fractal.coloring.OrbitTrap.NONE);
        FractalScene scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withColoring(coloring);
        try {
            fx(() -> {
                surface.resize(80, 60);
                surface.resizeBuffer(80, 60);
                return null;
            });
            renderStandard(surface, controller, scene, InteractiveRenderMode.FAST);
            RenderFrame frame = fx(() -> surface.completedRender().frame());
            int[] antialiased = pixels(surface);
            int[] base = basePixels(surface);
            assertFalse(java.util.Arrays.equals(base, antialiased), "The fixture must contain refined edges");

            renderStandard(surface, controller, scene, InteractiveRenderMode.BASE_ONLY);
            assertArrayEquals(base, pixels(surface));

            fx(() -> { surface.latency().arm(ignored -> {}); return null; });
            renderStandard(surface, controller, scene, InteractiveRenderMode.REFINED);
            var published = fx(() -> surface.latency().stop()).stream().map(InteractionLatency.Event::stage).toList();
            assertSame(frame, fx(() -> surface.completedRender().frame()));
            assertArrayEquals(antialiased, pixels(surface), "The finished AA colors must return");
            assertFalse(published.contains("aa_publish"),
                    "Finished AA must be restored, not recalculated: " + published);
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void paletteChangeInBaseOnlyNeverRestoresAntialiasingInTheOldPalette() throws Exception {
        var ice = new com.shangin.fractal.scene.ColoringSettings(PalettePreset.ICE);
        var histogram = new com.shangin.fractal.scene.ColoringSettings(ice.palette(), ice.paletteStops(),
                ice.colorScale(), ice.offset(), true, com.shangin.fractal.coloring.OrbitTrap.NONE);
        var other = new com.shangin.fractal.scene.ColoringSettings(PalettePreset.values()[1]);
        var recolored = new com.shangin.fractal.scene.ColoringSettings(other.palette(), other.paletteStops(),
                other.colorScale(), other.offset(), true, com.shangin.fractal.coloring.OrbitTrap.NONE);
        FractalScene scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withColoring(histogram);

        FractalSurface reference = fx(FractalSurface::new);
        FractalRenderController referenceController = fx(() -> new FractalRenderController(reference));
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        try {
            fx(() -> {
                for (FractalSurface target : java.util.List.of(reference, surface)) {
                    target.resize(80, 60);
                    target.resizeBuffer(80, 60);
                }
                return null;
            });
            renderStandard(reference, referenceController, scene.withColoring(recolored), InteractiveRenderMode.REFINED);
            int[] expected = pixels(reference);

            renderStandard(surface, controller, scene, InteractiveRenderMode.FAST);
            renderStandard(surface, controller, scene, InteractiveRenderMode.BASE_ONLY);
            CountDownLatch applied = new CountDownLatch(1);
            fx(() -> { controller.recolor(recolored, applied::countDown); return null; });
            assertTrue(applied.await(15, TimeUnit.SECONDS));
            renderStandard(surface, controller, scene.withColoring(recolored), InteractiveRenderMode.REFINED);

            assertArrayEquals(expected, pixels(surface), "AA colors from the old palette must not return");
        } finally {
            fx(() -> { controller.close(); referenceController.close(); return null; });
        }
    }

    @Test
    void undoAndRedoOfAntialiasingModesKeepFinishedAntialiasing() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE, false));
        Stage stage = fx(Stage::new);
        try {
            fx(() -> {
                stage.setScene(new Scene(view, 160, 120));
                stage.show();
                return null;
            });
            FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
            awaitSurfaceComplete(surface);
            // Histogram Coloring has no sample cache, so only kept AA colors avoid a new AA pass.
            settle(view, surface, () -> view.setHistogramColoring(true),
                    scene -> scene.coloring().histogramColoring());
            var fast = new AntialiasSettings(InteractiveRenderMode.FAST);
            var baseOnly = new AntialiasSettings(InteractiveRenderMode.BASE_ONLY);
            var refined = new AntialiasSettings(InteractiveRenderMode.REFINED);
            settle(view, surface, () -> view.setAntialiasSettings(fast), scene -> scene.antialiasing().equals(fast));
            RenderFrame frame = fx(() -> surface.completedRender().frame());
            int[] antialiased = pixels(surface);
            settle(view, surface, () -> view.setAntialiasSettings(baseOnly),
                    scene -> scene.antialiasing().equals(baseOnly));
            settle(view, surface, () -> view.setAntialiasSettings(refined),
                    scene -> scene.antialiasing().equals(refined));

            settle(view, surface, view::undoScene, scene -> scene.antialiasing().equals(baseOnly));
            assertSame(frame, fx(() -> surface.completedRender().frame()));
            assertFalse(java.util.Arrays.equals(antialiased, pixels(surface)), "Undo must show Base only");

            fx(() -> { surface.latency().arm(ignored -> {}); return null; });
            settle(view, surface, view::redoScene, scene -> scene.antialiasing().equals(refined));
            var published = fx(() -> surface.latency().stop()).stream().map(InteractionLatency.Event::stage).toList();
            assertSame(frame, fx(() -> surface.completedRender().frame()));
            assertArrayEquals(antialiased, pixels(surface), "Redo must restore the finished AA colors");
            assertFalse(published.contains("aa_publish"), "Redo must not recalculate AA: " + published);
        } finally {
            fx(() -> { view.close(); stage.close(); return null; });
        }
    }

    /** Runs a scene action and waits until the view is idle with a completed scene matching {@code done}. */
    private static void settle(FractalView view, FractalSurface surface, Runnable action,
                               java.util.function.Predicate<FractalScene> done) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            view.setOnRenderingChanged(active -> {
                var render = surface.completedRender();
                if (!active && render != null && done.test(render.scene())) completed.countDown();
            });
            action.run();
            return null;
        });
        assertTrue(completed.await(15, TimeUnit.SECONDS), "View must settle after the scene change");
        fx(() -> null);
    }

    private static void renderStandard(FractalSurface surface, FractalRenderController controller,
                                       FractalScene scene, InteractiveRenderMode mode) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            controller.cancelCurrent();
            boolean[] started = {false};
            controller.setOnRenderingChanged(active -> {
                if (active) started[0] = true;
                else if (started[0]) completed.countDown();
            });
            controller.render(scene.withAntialiasing(new AntialiasSettings(mode)),
                    new RenderTarget(surface.renderWidth(), surface.renderHeight(), RenderPriority.center()),
                    scene.fractal().defaultViewport());
            return null;
        });
        assertTrue(completed.await(15, TimeUnit.SECONDS));
    }

    @Test
    void switchingBetweenAntialiasingModesKeepsAFullyAntialiasedFrameWithoutRepublishing() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        var statuses = new CopyOnWriteArrayList<RenderStatus.State>();
        try {
            fx(() -> {
                controller.setOnRenderStatusChanged(status -> statuses.add(status.state()));
                return null;
            });
            render(surface, controller, camera, 80, 60, false, InteractiveRenderMode.FAST);
            RenderFrame frame = fx(() -> surface.completedRender().frame());
            int[] antialiased = pixels(surface);
            fx(() -> { surface.latency().arm(ignored -> {}); return null; });

            statuses.clear();
            render(surface, controller, camera, 80, 60, false, InteractiveRenderMode.REFINED);
            var published = fx(() -> surface.latency().stop()).stream().map(InteractionLatency.Event::stage).toList();
            assertSame(frame, fx(() -> surface.completedRender().frame()), "The visible frame must be kept");
            assertArrayEquals(antialiased, pixels(surface));
            assertEquals(java.util.List.of(RenderStatus.State.RENDERING, RenderStatus.State.COMPLETE), statuses);
            assertTrue(published.stream().noneMatch(java.util.Set.of(
                    "base_publish", "aa_publish", "frame_promoted")::contains), published.toString());
            assertEquals(InteractiveRenderMode.REFINED,
                    fx(() -> surface.completedRender().scene().antialiasing().renderMode()));
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void deepZoomModeSwitchesPublishOnlyTheSelectedPresentation() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalScene deep = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withViewport(new Viewport("-0.8267486182939549503119853330756",
                        "0.2150828768422976562284460839629", "1e-16"));
        var statuses = new CopyOnWriteArrayList<RenderStatus.State>();
        var deepZoom = new CopyOnWriteArrayList<Boolean>();
        try {
            fx(() -> {
                controller.setOnRenderStatusChanged(status -> statuses.add(status.state()));
                controller.setOnDeepZoomChanged(deepZoom::add);
                surface.resize(80, 60);
                surface.resizeBuffer(80, 60);
                return null;
            });
            render(surface, controller, deep, InteractiveRenderMode.BASE_ONLY);
            assertEquals(java.util.List.of(true), deepZoom.stream().distinct().toList());
            int[] base = basePixels(surface);
            assertArrayEquals(base, pixels(surface), "Deep base only must publish uncorrected base colors");
            assertEquals(RenderStatus.State.COMPLETE, statuses.getLast());
            RenderFrame frame = fx(() -> surface.completedRender().frame());

            render(surface, controller, deep, InteractiveRenderMode.FAST);
            int[] antialiased = pixels(surface);
            assertFalse(java.util.Arrays.equals(base, antialiased), "The deep fixture must contain refined edges");
            assertSame(frame, fx(() -> surface.completedRender().frame()),
                    "Deep antialiasing must refine the visible base frame without recalculating it");

            statuses.clear();
            render(surface, controller, deep, InteractiveRenderMode.BASE_ONLY);
            assertArrayEquals(base, pixels(surface), "Deep base only must not keep reused antialiased pixels");
            assertEquals(java.util.List.of(RenderStatus.State.RENDERING, RenderStatus.State.COMPLETE), statuses);

            render(surface, controller, deep, InteractiveRenderMode.REFINED);
            assertArrayEquals(antialiased, pixels(surface), "Deep antialiased display must refine the reused base");
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    /** Renders a fixed viewport with the supplied deep zoom mode and the default standard mode. */
    private static void render(FractalSurface surface, FractalRenderController controller, FractalScene scene,
                               InteractiveRenderMode deepMode) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            controller.cancelCurrent();
            boolean[] started = {false};
            controller.setOnRenderingChanged(active -> {
                if (active) started[0] = true;
                else if (started[0]) completed.countDown();
            });
            controller.render(scene.withAntialiasing(new AntialiasSettings(InteractiveRenderMode.REFINED, deepMode)),
                    new RenderTarget(surface.renderWidth(), surface.renderHeight(), RenderPriority.center()),
                    scene.fractal().defaultViewport());
            return null;
        });
        assertTrue(completed.await(60, TimeUnit.SECONDS));
    }

    @Test
    void baseOnlyReuseOfRetainedRefinedPreviewPublishesOnlyBaseColors() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        int[] expected = fx(() -> {
            var calculator = new com.shangin.fractal.render.FractalCalculator(
                    FractalPreset.MANDELBROT.createFormula());
            Viewport viewport = FractalPreset.MANDELBROT.defaultViewport();
            var refined = RenderFrame.create(new com.shangin.fractal.render.RenderRequest(
                    calculator, viewport, 8, 8, 20));
            var other = RenderFrame.create(new com.shangin.fractal.render.RenderRequest(
                    calculator, viewport.zoom(0.5), 8, 8, 20));
            var target = RenderFrame.create(refined.request());
            var wholeFrame = new RenderRegion(0, 0, 8, 8);
            var coloring = new com.shangin.fractal.coloring.SmoothPaletteColoring(PalettePreset.ICE.palette());
            refined.validity().markReady(wholeFrame);
            target.validity().markReady(wholeFrame);
            surface.resize(8, 8);
            surface.resizeBuffer(8, 8);

            // A refined render has published one AA tile when the user zooms away.
            surface.beginProgressiveRender(refined, null, InteractiveRenderMode.REFINED);
            surface.displayProgress(new com.shangin.fractal.render.RenderProgressBatch(
                    refined, java.util.List.of(wholeFrame)), coloring, InteractiveRenderMode.REFINED);
            surface.beginRefinedRender(refined);
            int[] marker = new int[4 * 4];
            java.util.Arrays.fill(marker, 0xFF123456);
            surface.displayRefinedTile(refined, new RenderRegion(0, 0, 4, 4), marker);
            surface.beginProgressiveRender(other, null, InteractiveRenderMode.REFINED);

            // Base only returns to the retained viewport before the other render completes.
            var zeroShift = new com.shangin.fractal.render.PixelShift(0, 0);
            surface.invalidateRefinement();
            assertFalse(surface.reuseProgressivePixels(refined, target, zeroShift, coloring, true));
            surface.beginProgressiveRender(target, refined, InteractiveRenderMode.BASE_ONLY);
            assertTrue(surface.reuseDisplayedPixels(refined, zeroShift),
                    "The fixture must reuse the retained refined preview");
            surface.displayReadyPixelsPreservingRefinement(target, coloring);
            surface.completeProgressiveRender(target, FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withViewport(viewport)
                    .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY)));

            int[] colors = new int[8 * 8];
            new com.shangin.fractal.render.FractalColorizer().color(target.samplePlane(),
                    java.nio.IntBuffer.wrap(colors), coloring);
            return colors;
        });
        assertArrayEquals(expected, pixels(surface), "Base only must not keep retained antialiased pixels");
    }

    private static int cachedAntialiasSamples(FractalRenderController controller) throws Exception {
        var serviceField = FractalRenderController.class.getDeclaredField("antialiasService");
        serviceField.setAccessible(true);
        var service = serviceField.get(controller);
        var cacheField = service.getClass().getDeclaredField("sampleCache");
        cacheField.setAccessible(true);
        return ((com.shangin.fractal.render.AntialiasSampleCache) cacheField.get(service)).size();
    }

    private static int[] basePixels(FractalSurface surface) throws Exception {
        return fx(() -> {
            var completed = surface.completedRender();
            var samples = completed.frame().samplePlane();
            int[] colors = new int[samples.size()];
            new com.shangin.fractal.render.FractalColorizer().color(samples, java.nio.IntBuffer.wrap(colors),
                    completed.scene().coloring().createStrategy(samples));
            return colors;
        });
    }

    @Test
    void defaultResizeUpdatesDimensionsEvenWhenSpareBufferMatches() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        try {
            render(surface, controller, camera, 80, 60, false);
            render(surface, controller, camera, 120, 100, false);
            render(surface, controller, camera, 80, 60, false);
            assertEquals(80, surface.renderWidth());
            assertEquals(60, surface.renderHeight());
            assertEquals(80 * 60, pixels(surface).length);
            assertTrue(camera.isDefaultView());
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    private static FractalScene scene(FractalCamera camera) {
        return FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withViewport(camera.viewport());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {60, 120})
    @EnabledIfSystemProperty(named = "fractal.fx.fullscreen", matches = "true")
    void nativeFullscreenZoomMustFillEntireImage(int zoomSteps) throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        javafx.stage.Stage stage = fx(javafx.stage.Stage::new);
        try {
            changeView(view, surface, () -> {
                var bounds = javafx.stage.Screen.getPrimary().getBounds();
                stage.setScene(new javafx.scene.Scene(view, bounds.getWidth() * .75, bounds.getHeight() * .75));
                stage.show();
            });
            changeView(view, surface, () -> {
                for (int i = 0; i < zoomSteps; i++) view.zoom(true);
                view.setCenter(new java.math.BigDecimal("-0.743643887037151"),
                        new java.math.BigDecimal("0.131825904205330"));
            }, 90);
            int[] original = pixels(surface);
            for (int cycle = 0; cycle < 2; cycle++) {
                fx(() -> { stage.setFullScreen(true); return null; });
                // Include the native macOS transition and its last geometry events.
                CountDownLatch settled = new CountDownLatch(1);
                fx(() -> {
                    var pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
                    pause.setOnFinished(event -> settled.countDown());
                    pause.play();
                    return null;
                });
                assertTrue(settled.await(15, TimeUnit.SECONDS));
                awaitSurfaceComplete(surface);
                fx(() -> {
                    var parameters = new javafx.scene.SnapshotParameters();
                    parameters.setTransform(javafx.scene.transform.Transform.scale(
                            surface.renderWidth() / surface.getWidth(), surface.renderHeight() / surface.getHeight()));
                    var image = surface.snapshot(parameters, null);
                    int width = (int) image.getWidth(), height = (int) image.getHeight();
                    int[] argb = new int[width * height];
                    image.getPixelReader().getPixels(0, 0, width, height,
                            PixelFormat.getIntArgbInstance(), argb, 0, width);
                    var output = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    output.setRGB(0, 0, width, height, argb, 0, width);
                    javax.imageio.ImageIO.write(output, "png", new java.io.File("target/fullscreen-zoom-" + zoomSteps + "-qa.png"));
                    var base = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
                    int[] backingPixels = new int[width * height];
                    base.getPixelReader().getPixels(0, 0, width, height,
                            PixelFormat.getIntArgbInstance(), backingPixels, 0, width);
                    assertArrayEquals(backingPixels, argb, "Visible surface must show every rendered pixel");
                    var completed = surface.completedRender();
                    assertEquals(surface.renderWidth(), completed.frame().request().width());
                    assertEquals(surface.renderHeight(), completed.frame().request().height());
                    for (int layer = 1; layer < 3; layer++) {
                        assertNull(((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage(), "Frame must finish");
                    }
                    return null;
                });
                assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
                fx(() -> { stage.setFullScreen(false); return null; });
                CountDownLatch windowSettled = new CountDownLatch(1);
                fx(() -> {
                    var pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
                    pause.setOnFinished(event -> windowSettled.countDown());
                    pause.play();
                    return null;
                });
                assertTrue(windowSettled.await(10, TimeUnit.SECONDS));
                awaitSurfaceComplete(surface);
                assertArrayEquals(original, pixels(surface), "Each return must preserve the original zoomed center");
            }
        } finally {
            fx(() -> { stage.close(); view.close(); return null; });
        }
    }

    @Test
    void noOpTrackpadPanMustNotCancelPendingRender() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(80, 60); view.layout(); });
            changeView(view, surface, () -> {
                view.zoom(true);
                view.zoom(false);
                // At the overview bounds this scroll does not move the camera.
                view.fireEvent(new javafx.scene.input.ScrollEvent(
                        javafx.scene.input.ScrollEvent.SCROLL, 40, 30, 40, 30,
                        false, false, false, false, false, false,
                        1, 0, 1, 0,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            });
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void clickWithoutDraggingMustNotCancelPendingZoom() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(80, 60); view.layout(); });
            changeView(view, surface, () -> {
                view.zoom(true);
                for (boolean pressed : new boolean[]{true, false}) {
                    view.fireEvent(new javafx.scene.input.MouseEvent(
                            pressed ? javafx.scene.input.MouseEvent.MOUSE_PRESSED
                                    : javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                            40, 30, 40, 30, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, pressed, false, false, false, false, true, null));
                }
            });
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void zoomBetweenResizeEventAndLayoutMustNotRestoreOverviewPreview() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(80, 60); view.layout(); });
            changeView(view, surface, () -> {
                view.resize(120, 100);
                view.zoom(true);
                view.layout();
                var base = (ImageView) surface.getChildrenUnmodifiable().getFirst();
                assertEquals(80.0 * 1.25 / 120, base.getLocalToParentTransform().getMxx(), 1e-8,
                        "A zoom arriving during resize must stay a zoom preview, not an overview stretch");
            });
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void initialResizePreviewMustFillWindowThroughoutInterruptedAspectChanges() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(600, 400); view.layout(); });
            int[] originalPixels = pixels(surface);
            CountDownLatch resized = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            fx(() -> {
                new javafx.animation.AnimationTimer() {
                    int step;
                    final int[][] sizes = {{1000, 700}, {1000, 760}, {700, 760}, {700, 400}, {600, 400}};
                    public void handle(long now) {
                        try {
                            view.resize(sizes[step][0], sizes[step][1]);
                            view.layout();
                            var base = (ImageView) surface.getChildrenUnmodifiable().getFirst();
                            assertEquals(0, base.getBoundsInParent().getMinX(), 1e-8,
                                    "Initial preview must not reveal side bands");
                            assertEquals(surface.getWidth(), base.getBoundsInParent().getWidth(), 1e-8);
                            for (int layer = 1; layer < 3; layer++) {
                                assertNull(((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage(),
                                        "New tiles must not mix with a differently stretched initial frame");
                            }
                            if (++step == sizes.length) { stop(); resized.countDown(); }
                        } catch (Throwable error) {
                            failure.set(error); stop(); resized.countDown();
                        }
                    }
                }.start();
                return null;
            });
            assertTrue(resized.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            changeView(view, surface, () -> { view.resize(602, 402); view.layout(); });
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
            changeView(view, surface, () -> { view.resize(600, 400); view.layout(); });
            assertArrayEquals(originalPixels, pixels(surface),
                    "Returning to the initial dimensions must restore the same fractal");
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void resizeMustFinishUnpublishedRefinementEvenWhenAllSamplesAreReady() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        try {
            camera.resize(80, 60);
            camera.zoomBy(40, 30, 0.25, 80, 60, 80, 60);
            render(surface, controller, camera, 80, 60, false);
            fx(() -> {
                // Model a resize arriving between base completion and AA publication.
                var frame = surface.completedRender().frame();
                surface.beginProgressiveRender(frame, null, InteractiveRenderMode.REFINED);
                surface.beginRefinedRender(frame);
                int[] center = new int[20 * 20];
                java.util.Arrays.fill(center, 0xFF123456);
                surface.displayRefinedTile(frame,
                        new com.shangin.fractal.render.RenderRegion(30, 20, 20, 20), center);
                return null;
            });
            // A duplicate geometry event must not promote the partly transparent AA layer.
            render(surface, controller, camera, 80, 60, true);
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255),
                    "Completed resize must not leave transparent tiles around the center");
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void viewResizeEventsKeepOddSizedRetinaFramesCentered() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            fx(() -> { surface.setOutputScale(2, 2); return null; });
            changeView(view, surface, () -> { view.resize(81, 61); view.layout(); });
            changeView(view, surface, () -> { view.resize(121, 101); view.layout(); });
            changeView(view, surface, () -> { view.resize(81, 61); view.layout(); });
            changeView(view, surface, () -> view.zoom(true));
            int[] before = pixels(surface);
            changeView(view, surface, () -> { view.resize(121, 101); view.layout(); });
            int[] expanded = pixels(surface);
            for (int y = 0; y < 122; y++) {
                for (int x = 0; x < 162; x++) {
                    assertEquals(before[y * 162 + x], expanded[(y + 40) * 242 + x + 40]);
                }
            }
            changeView(view, surface, () -> { view.resize(81, 61); view.layout(); });
            assertArrayEquals(before, pixels(surface));
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void dragAfterExpandedAndCroppedResizeKeepsIterationLimitForReuse() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(100, 80); view.layout(); });
            changeView(view, surface, () -> {
                for (int zoom = 0; zoom < 5; zoom++) view.zoom(true);
            });

            changeView(view, surface, () -> { view.resize(160, 120); view.layout(); });
            int expandedIterations = fx(() -> surface.completedRender().frame().request().maxIterations());
            changeView(view, surface, () -> drag(view, 80, 60, 92, 67));
            assertEquals(expandedIterations,
                    fx(() -> surface.completedRender().frame().request().maxIterations()),
                    "A drag after expansion must remain compatible with the resized frame");

            changeView(view, surface, () -> { view.resize(100, 80); view.layout(); });
            int croppedIterations = fx(() -> surface.completedRender().frame().request().maxIterations());
            changeView(view, surface, () -> drag(view, 50, 40, 42, 46));
            assertEquals(croppedIterations,
                    fx(() -> surface.completedRender().frame().request().maxIterations()),
                    "A drag after cropping must remain compatible with the resized frame");
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    private static void drag(FractalView view, double fromX, double fromY, double toX, double toY) {
        view.fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                fromX, fromY, fromX, fromY, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true, null));
        view.fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                toX, toY, toX, toY, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
        view.fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                toX, toY, toX, toY, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, false, null));
    }

    private static void awaitSurfaceComplete(FractalSurface surface) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        var timer = fx(() -> {
            var checker = new javafx.animation.AnimationTimer() {
                @Override public void handle(long now) {
                    var frame = surface.completedRender();
                    if (frame == null || frame.frame().request().width() != surface.renderWidth()
                            || frame.frame().request().height() != surface.renderHeight()) return;
                    for (int layer = 1; layer < 3; layer++) {
                        if (((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage() != null) return;
                    }
                    stop();
                    completed.countDown();
                }
            };
            checker.start();
            return checker;
        });
        try {
            assertTrue(completed.await(90, TimeUnit.SECONDS), "Resized surface must finish without input");
        } finally {
            fx(() -> { timer.stop(); return null; });
        }
    }

    private static void changeView(FractalView view, FractalSurface surface, Runnable action) throws Exception {
        changeView(view, surface, action, 15);
    }

    private static void changeView(FractalView view, FractalSurface surface, Runnable action, int timeoutSeconds) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            var previous = surface.completedRender();
            view.setOnRenderingChanged(active -> {
                if (!active && surface.completedRender() != null
                        && (previous == null || previous.frame() != surface.completedRender().frame())) {
                    completed.countDown();
                }
            });
            action.run();
            return null;
        });
        assertTrue(completed.await(timeoutSeconds, TimeUnit.SECONDS), "View must complete the new frame");
    }

    private static void render(FractalSurface surface, FractalRenderController controller,
                               FractalCamera camera, int width, int height, boolean resize) throws Exception {
        render(surface, controller, camera, width, height, resize, InteractiveRenderMode.REFINED);
    }

    private static void render(FractalSurface surface, FractalRenderController controller,
                               FractalCamera camera, int width, int height, boolean resize,
                               InteractiveRenderMode mode) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            controller.cancelCurrent();
            boolean[] started = {false};
            controller.setOnRenderingChanged(active -> {
                if (active) started[0] = true;
                else if (started[0]) completed.countDown();
            });
            int oldHeight = surface.renderHeight();
            surface.resize(width, height);
            surface.resizeBuffer(width, height);
            if (oldHeight >= 2) {
                camera.resize(width, height, oldHeight, surface.renderHeight());
            } else {
                camera.resize(width, height);
            }
            controller.render(scene(camera).withAntialiasing(new AntialiasSettings(mode)),
                    new RenderTarget(surface.renderWidth(), surface.renderHeight(),
                    RenderPriority.center()), camera.defaultViewport(width, height), resize);
            return null;
        });
        assertTrue(completed.await(15, TimeUnit.SECONDS));
    }

    private static int[] pixels(FractalSurface surface) throws Exception {
        return fx(() -> {
            var image = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
            int width = (int) image.getWidth(), height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                    PixelFormat.getIntArgbPreInstance(), pixels, 0, width);
            return pixels;
        });
    }
}
