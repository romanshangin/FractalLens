package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderBackend;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.TileTimingStats;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacSystemMenuFxTest {
    @TempDir Path directory;

    @BeforeAll static void startFx() throws Exception {
        var started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterAll static void stopFx() { Platform.exit(); }

    @Test void retainsNativeCommandsWithoutFocusedMainWindowAndRestoresFocus() throws Exception {
        var stage = fx(Stage::new);
        var other = fx(Stage::new);
        var view = fx(() -> new MainView(stage, new LastSessionStore(directory.resolve("session.json"))));
        try {
            fx(() -> {
                stage.setScene(new Scene(view, 320, 240));
                stage.show(); stage.toFront(); stage.requestFocus();
                return null;
            });
            await(stage::isFocused);
            await(() -> ((FractalView) view.getCenter()).hasCompletedFrame());
            var expected = List.of("File", "Edit", "View", "Fractal", "Go to", "Color", "Render", "Window", "Help");
            assertEquals(expected, nativeCommands());

            // An unowned window with no menu exercises Glass's application-default
            // path, also used when the main window loses focus during a Space switch.
            fx(() -> {
                other.setScene(new Scene(new Pane(), 200, 120));
                other.show(); other.toFront(); other.requestFocus();
                return null;
            });
            await(() -> other.isFocused() && !stage.isFocused());
            assertEquals(expected, nativeCommands(), "Native commands must survive main-window focus loss");

            fx(() -> { stage.toFront(); stage.requestFocus(); return null; });
            await(stage::isFocused);
            assertEquals(expected, nativeCommands(), "Commands must remain installed without pointer input");

            fx(() -> { other.close(); stage.close(); view.close(); view.close(); return null; });
            fx(() -> { other.show(); other.toFront(); other.requestFocus(); return null; });
            await(other::isFocused);
            assertEquals(List.of(), nativeCommands(), "Closed views must release the application-default commands");
        } finally {
            fx(() -> { other.close(); stage.close(); view.close(); return null; });
        }
    }

    @Test void appearanceCommandsFollowStartupAndSubsequentRenderWithoutValidation() throws Exception {
        var gate = new AtomicReference<>(new CountDownLatch(1));
        var cpu = new DirectDoubleRenderBackend();
        RenderBackend backend = new RenderBackend() {
            @Override public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                                                Consumer<RenderRegion> progress,
                                                Consumer<TileTimingStats> timing) throws InterruptedException {
                assertTrue(gate.get().await(15, TimeUnit.SECONDS), "Render gate was not released");
                return cpu.render(frame, cancelled, progress, timing);
            }
            @Override public void close() { cpu.close(); }
        };
        var stage = fx(Stage::new);
        var view = fx(() -> new MainView(stage, new LastSessionStore(directory.resolve("gated.json")),
                new FractalRenderService(backend)));
        var bar = (MainMenuBar) fx(view::getTop);
        var canvas = (FractalView) fx(view::getCenter);
        try {
            fx(() -> {
                stage.setScene(new Scene(view, 320, 240));
                stage.show(); stage.toFront(); stage.requestFocus();
                return null;
            });
            await(stage::isFocused);
            assertAppearanceCommands(bar, true);
            fx(() -> {
                AppKitMenuProbe.updateSubmenu("Color");
                AppKitMenuProbe.updateSubmenu("Render");
                return null;
            });
            assertAppearanceCommands(bar, true);
            assertFalse(fx(() -> AppKitMenuProbe.enabled("File/Export PNG…")));
            assertTrue(fx(() -> AppKitMenuProbe.enabled("File/Close Window")));
            assertTrue(fx(() -> AppKitMenuProbe.enabled("Help/FractalLens Help")));
            gate.get().countDown();
            await(() -> canvas.hasCompletedFrame() && !item(bar, "Color", "Edit Palette…").isDisable());
            assertAppearanceCommands(bar, false);
            assertTrue(fx(() -> AppKitMenuProbe.enabled("File/Export PNG…")));

            gate.set(new CountDownLatch(1));
            fx(() -> { canvas.zoom(true); return null; });
            await(() -> item(bar, "Color", "Edit Palette…").isDisable());
            assertAppearanceCommands(bar, true);
            gate.get().countDown();
            await(() -> !item(bar, "Color", "Edit Palette…").isDisable());
            assertAppearanceCommands(bar, false);
        } finally {
            gate.get().countDown();
            fx(() -> { stage.close(); view.close(); return null; });
        }
    }

    private static MenuItem item(MainMenuBar bar, String menu, String title) {
        return bar.getMenus().stream().filter(candidate -> menu.equals(candidate.getText()))
                .findFirst().orElseThrow().getItems().stream()
                .filter(candidate -> title.equals(candidate.getText())).findFirst().orElseThrow();
    }

    private static void assertAppearanceCommands(MainMenuBar bar, boolean busy) throws Exception {
        fx(() -> {
            for (String title : List.of("Color", "Render")) {
                Menu menu = bar.getMenus().stream().filter(candidate -> title.equals(candidate.getText()))
                        .findFirst().orElseThrow();
                assertFalse(menu.isDisable(), "Top-level menus must remain openable");
                assertItems(menu, title, busy);
            }
            return null;
        });
    }

    private static void assertItems(Menu menu, String path, boolean busy) {
        for (MenuItem item : menu.getItems()) {
            if (item instanceof SeparatorMenuItem) continue;
            String itemPath = path + "/" + item.getText();
            boolean unavailable = busy || "Deep Zoom Antialiasing".equals(item.getText())
                    || "Edit Julia Parameters…".equals(item.getText());
            assertEquals(item instanceof Menu ? false : unavailable, item.isDisable(), itemPath);
            assertEquals(item instanceof Menu || !unavailable, AppKitMenuProbe.enabled(itemPath),
                    "Native state must match without hovering or reopening: " + itemPath);
            if (item instanceof Menu submenu) assertItems(submenu, itemPath, busy);
        }
    }

    private static List<String> nativeCommands() throws Exception {
        return fx(() -> {
            try {
                var titles = AppKitMenuProbe.mainMenuTitles();
                return titles.subList(1, titles.size()); // The application menu belongs to Glass.
            } catch (Throwable failure) {
                throw new AssertionError("Cannot inspect the native system menu", failure);
            }
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var ready = new CountDownLatch(1);
        var timer = fx(() -> {
            var checker = new AnimationTimer() {
                @Override public void handle(long now) {
                    if (condition.getAsBoolean()) { stop(); ready.countDown(); }
                }
            };
            checker.start();
            return checker;
        });
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Expected JavaFX state did not appear");
        } finally {
            fx(() -> { timer.stop(); return null; });
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
