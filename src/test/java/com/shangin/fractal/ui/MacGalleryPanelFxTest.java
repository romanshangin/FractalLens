package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.SceneGallery;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production gallery on macOS: the AppKit panel, driven by real key events. */
@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacGalleryPanelFxTest {
    private static final short RETURN = 36;
    private static final short ESCAPE = 53;
    private static final short RIGHT = 124;
    /** Arrow keys carry the function and numeric pad flags, as from a real keyboard. */
    private static final long ARROW_FLAGS = 0x800000L | 0x200000L;

    private Stage stage;
    private FractalView view;
    private SceneGalleryWindow gallery;
    private final AtomicBoolean rendering = new AtomicBoolean(true);

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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!fx(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + description);
            Thread.sleep(20);
        }
    }

    @BeforeEach
    void open() throws Exception {
        assertTrue(MacGalleryPanel.isAvailable());
        fx(() -> {
            stage = new Stage();
            view = new FractalView(FractalPreset.MANDELBROT, PalettePreset.values()[0], false);
            view.setOnRenderingChanged(rendering::set);
            stage.setScene(new Scene(new BorderPane(view), 640, 420));
            stage.show();
            gallery = new SceneGalleryWindow(stage, view, () -> false);
            return null;
        });
        awaitFx("initial render", () -> !rendering.get());
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            gallery.close();
            FractalDialogs.close(stage);
            view.close();
            stage.close();
            return null;
        });
    }

    private MemorySegment session() throws Exception {
        var field = MacGalleryPanel.class.getDeclaredField("handle");
        field.setAccessible(true);
        long handle = field.getLong(gallery.presentation());
        if (handle == 0) throw new AssertionError("The native gallery panel must be open");
        return MemorySegment.ofAddress(handle);
    }

    private MemorySegment property(String name) throws Exception {
        return (MemorySegment) AppKitMenuProbe.message(session(), name, ADDRESS);
    }

    private long count(String collection) throws Exception {
        return (long) AppKitMenuProbe.message(property(collection), "count", JAVA_LONG);
    }

    private boolean panelVisible() throws Exception {
        return (byte) AppKitMenuProbe.message(property("panel"), "isVisible", JAVA_BYTE) != 0;
    }

    private long items() throws Exception {
        var collection = property("collection");
        long sections = (long) AppKitMenuProbe.message(collection, "numberOfSections", JAVA_LONG);
        long items = 0;
        for (long section = 0; section < sections; section++) {
            items += (long) AppKitMenuProbe.message(collection, "numberOfItemsInSection:", JAVA_LONG,
                    new MemoryLayout[]{JAVA_LONG}, section);
        }
        return items;
    }

    /** Sends a key-down event through NSApplication, as the keyboard does. */
    private void key(short keyCode) throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            var panel = property("panel");
            var characters = (MemorySegment) AppKitMenuProbe.message(AppKitParameterProbe.objcClass("NSString", arena),
                    "stringWithUTF8String:", ADDRESS, new MemoryLayout[]{ADDRESS},
                    arena.allocateFrom(keyCode == RETURN ? "\r" : keyCode == ESCAPE ? "\u001b" : "\uf703"));
            var point = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE);
            var event = (MemorySegment) AppKitMenuProbe.message(AppKitParameterProbe.objcClass("NSEvent", arena),
                    "keyEventWithType:location:modifierFlags:timestamp:windowNumber:context:characters:"
                            + "charactersIgnoringModifiers:isARepeat:keyCode:",
                    ADDRESS, new MemoryLayout[]{JAVA_LONG, point, JAVA_LONG, JAVA_DOUBLE,
                            JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_BYTE, JAVA_SHORT},
                    10L, arena.allocate(point), keyCode == RIGHT ? ARROW_FLAGS : 0L, 0.0,
                    (long) AppKitMenuProbe.message(panel, "windowNumber", JAVA_LONG), MemorySegment.NULL,
                    characters, characters, (byte) 0, keyCode);
            var application = (MemorySegment) AppKitMenuProbe.message(
                    AppKitParameterProbe.objcClass("NSApplication", arena), "sharedApplication", ADDRESS);
            AppKitMenuProbe.message(application, "sendEvent:", null, new MemoryLayout[]{ADDRESS}, event);
        }
    }

    /** The selected section and item. */
    private java.util.List<Long> selection() throws Exception {
        var paths = (MemorySegment) AppKitMenuProbe.message(property("collection"), "selectionIndexPaths", ADDRESS);
        assertEquals(1L, (long) AppKitMenuProbe.message(paths, "count", JAVA_LONG));
        var path = (MemorySegment) AppKitMenuProbe.message(paths, "anyObject", ADDRESS);
        return java.util.List.of((long) AppKitMenuProbe.message(path, "section", JAVA_LONG),
                (long) AppKitMenuProbe.message(path, "item", JAVA_LONG));
    }

    @Test
    void showsEveryPlaceWithPreviewsAtThePanelScaleAndOpensThePlaceWithReturn() throws Exception {
        var catalog = SceneGallery.loadBundled();
        FractalScene before = fx(() -> {
            gallery.show();
            assertInstanceOf(MacGalleryPanel.class, gallery.presentation());
            assertTrue(panelVisible());
            return view.sceneSnapshot();
        });
        awaitFx("native places", () -> {
            try { return items() == catalog.entries().size(); } catch (Exception e) { throw new AssertionError(e); }
        });
        awaitFx("native previews", () -> {
            try { return count("images") == catalog.entries().size(); } catch (Exception e) { throw new AssertionError(e); }
        });
        fx(() -> {
            assertEquals(catalog.groups().size(), (long) AppKitMenuProbe.message(property("collection"),
                    "numberOfSections", JAVA_LONG));
            assertEquals(0, count("failed"));
            // The panel reports its own backing scale; the owner is on the same display here.
            assertEquals(Math.max(stage.getOutputScaleX(), stage.getOutputScaleY()),
                    gallery.presentation().outputScale(), 0.0);
            // Arrow keys select through AppKit; Return opens the selected place.
            // The first place starts selected; the right arrow moves to the second, Return opens it.
            assertEquals(java.util.List.of(0L, 0L), selection());
            key(RIGHT);
            assertEquals(java.util.List.of(0L, 1L), selection());
            key(RETURN);
            return null;
        });
        awaitFx("place opened", () -> !gallery.isShowing());
        fx(() -> {
            FractalScene opened = view.sceneSnapshot();
            assertNotEquals(before, opened);
            var second = catalog.groups().get(FractalPreset.MANDELBROT).get(1);
            assertEquals(second.sceneWith(before.antialiasing()), opened);
            view.undoScene();
            assertEquals(before, view.sceneSnapshot());
            return null;
        });
    }

    @Test
    void escapeClosesThePanelAndReopeningShowsCachedPreviews() throws Exception {
        int places = SceneGallery.loadBundled().entries().size();
        fx(() -> {
            gallery.show();
            return null;
        });
        awaitFx("native previews", () -> {
            try { return count("images") == places; } catch (Exception e) { throw new AssertionError(e); }
        });
        var first = fx(() -> gallery.presentation());
        fx(() -> {
            key(ESCAPE);
            return null;
        });
        awaitFx("panel closed", () -> !gallery.isShowing());
        fx(() -> {
            gallery.show();
            assertInstanceOf(MacGalleryPanel.class, gallery.presentation());
            assertTrue(gallery.presentation() != first);
            // Cached previews arrive with the entries, before any new rendering.
            assertEquals(places, count("images"));
            return null;
        });
    }

    private static final String PROBLEM_CATALOG = """
            {"catalogVersion": 1, "entries": [
              {"id": "first", "name": "First", "description": "Renders.", "fractal": "MANDELBROT",
               "viewport": {"centerReal": "-0.75", "centerImaginary": "0.1", "scale": "0.15"},
               "coloring": {"palette": "ICE"}},
              {"id": "deep-trap", "name": "Deep Trap", "description": "No backend renders this.",
               "fractal": "MANDELBROT",
               "viewport": {"centerReal": "0", "centerImaginary": "1", "scale": "2.4e-20"},
               "coloring": {"palette": "ICE", "orbitTrap": "POINT"}},
              {"id": "broken", "name": "Broken", "description": "Unknown palette.", "fractal": "TRICORN",
               "viewport": {"centerReal": "0", "centerImaginary": "0", "scale": "1"},
               "coloring": {"palette": "NEON"}}
            ]}""";

    /** Replaces the production gallery with one on the native panel and the given catalog. */
    private void nativeGallery(SceneGalleryWindow.CatalogSource catalog) throws Exception {
        fx(() -> {
            gallery.close();
            gallery = new SceneGalleryWindow(stage, view, () -> false, catalog,
                    SceneGalleryWindow::cpuPreviewRenderer, MacGalleryPanel::show);
            gallery.show();
            assertInstanceOf(MacGalleryPanel.class, gallery.presentation());
            return null;
        });
    }

    private boolean hidden(String view) throws Exception {
        return AppKitParameterProbe.hidden(property(view));
    }

    @Test
    void catalogFailureShowsTheErrorInsteadOfPlaces() throws Exception {
        nativeGallery(() -> { throw new java.io.IOException("missing"); });
        awaitFx("native error", () -> {
            try { return !hidden("error"); } catch (Exception e) { throw new AssertionError(e); }
        });
        fx(() -> {
            assertEquals(SceneGalleryWindow.LOAD_ERROR, AppKitParameterProbe.stringValue(property("error")));
            assertTrue(hidden("scroll"));
            assertTrue(hidden("notice"));
            assertEquals(0, items());
            return null;
        });
    }

    @Test
    void skippedEntriesFailedPreviewsAndRejectedPlacesAreShownWithoutClosingThePanel() throws Exception {
        nativeGallery(() -> SceneGallery.read(PROBLEM_CATALOG));
        awaitFx("native places", () -> {
            try { return items() == 2; } catch (Exception e) { throw new AssertionError(e); }
        });
        // The real renderer draws the first place and cannot draw the deep orbit-trap place.
        awaitFx("preview results", () -> {
            try { return count("images") == 1 && count("failed") == 1; } catch (Exception e) { throw new AssertionError(e); }
        });
        FractalScene before = fx(() -> {
            assertFalse(hidden("notice"));
            assertEquals(SceneGalleryWindow.PARTIAL_HINT, AppKitParameterProbe.stringValue(property("notice")));
            assertTrue(hidden("error"));
            assertFalse(hidden("scroll"));
            var failed = item(0, 1);
            var failure = (MemorySegment) AppKitMenuProbe.message(failed, "failure", ADDRESS);
            assertFalse(AppKitParameterProbe.hidden(failure));
            assertEquals(SceneGalleryWindow.PREVIEW_ERROR, AppKitParameterProbe.stringValue(failure));
            FractalScene current = view.sceneSnapshot();
            // Right, then Return: the deep orbit-trap place is rejected before any change.
            key(RIGHT);
            assertEquals(java.util.List.of(0L, 1L), selection());
            key(RETURN);
            return current;
        });
        fx(() -> {
            assertEquals(before, view.sceneSnapshot());
            assertTrue(gallery.isShowing());
            assertTrue(panelVisible());
            assertFalse(view.canUndo());
            return null;
        });
    }

    private MemorySegment item(long section, long index) throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            var path = (MemorySegment) AppKitMenuProbe.message(AppKitParameterProbe.objcClass("NSIndexPath", arena),
                    "indexPathForItem:inSection:", ADDRESS, new MemoryLayout[]{JAVA_LONG, JAVA_LONG}, index, section);
            var item = (MemorySegment) AppKitMenuProbe.message(property("collection"), "itemAtIndexPath:", ADDRESS,
                    new MemoryLayout[]{ADDRESS}, path);
            if (item.address() == 0) throw new AssertionError("The item must be visible");
            return item;
        }
    }

    @Test
    void closingTheGalleryDisposesThePanelWithoutCallbacks() throws Exception {
        fx(() -> {
            gallery.show();
            assertTrue(panelVisible());
            var panel = property("panel");
            gallery.close();
            assertFalse(gallery.isShowing());
            assertFalse((byte) AppKitMenuProbe.message(panel, "isVisible", JAVA_BYTE) != 0);
            // A closed gallery stays closed.
            gallery.show();
            assertFalse(gallery.isShowing());
            return null;
        });
    }
}
