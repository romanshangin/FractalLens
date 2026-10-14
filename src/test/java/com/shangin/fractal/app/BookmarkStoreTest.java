package com.shangin.fractal.app;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.FractalSceneJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BookmarkStoreTest {
    @TempDir Path directory;
    private final FractalScene scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
            .withViewport(new Viewport("-0.743643887037151123456789", "0.131825904205330987654321", "1e-90"));

    @Test void persistsNamedExactScenesAcrossRestartAndDeletesThem() throws Exception {
        var store = new BookmarkStore(directory);
        assertTrue(store.load().isEmpty());
        store.save("  Deep / view 🌌  ", scene);
        var restarted = new BookmarkStore(directory);
        assertEquals(new BookmarkStore.Bookmark("Deep / view 🌌", scene), restarted.load().getFirst());
        try (var files = Files.list(directory)) {
            assertEquals(scene, FractalSceneJson.read(Files.readString(files.findFirst().orElseThrow())));
        }
        restarted.delete("Deep / view 🌌");
        assertTrue(store.load().isEmpty());
    }

    @Test void replacementRetainsRecoveryAndDeleteRemovesBothGenerations() throws Exception {
        var store = new BookmarkStore(directory);
        store.save("Deep", scene);
        store.save("Deep", scene.withColoring(new com.shangin.fractal.scene.ColoringSettings(PalettePreset.FIRE)));
        Path primary;
        try (var files = Files.list(directory)) {
            primary = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        Files.writeString(primary, "broken");
        assertEquals(scene, store.load().getFirst().scene());
        store.delete("Deep");
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void validatesNamesAndReportsCorruptionWithoutRemovingFiles() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> BookmarkStore.validateName(" "));
        assertThrows(IllegalArgumentException.class, () -> BookmarkStore.validateName("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> BookmarkStore.validateName("x".repeat(81)));
        var store = new BookmarkStore(directory);
        store.save("Deep", scene);
        Path primary;
        try (var files = Files.list(directory)) { primary = files.findFirst().orElseThrow(); }
        Files.writeString(primary, "broken");
        assertThrows(IOException.class, store::load);
        assertTrue(Files.exists(primary));
    }

    @Test void namesRemainDistinctOnCaseInsensitiveFilesystems() throws Exception {
        var store = new BookmarkStore(directory);
        store.save("A", scene);
        store.save("a", scene);
        assertEquals(2, store.load().size());
    }

    @Test void countsUnicodeCharactersAndPersistsLongNamesWithoutOversizedFilenames() throws Exception {
        var store = new BookmarkStore(directory);
        for (String name : java.util.List.of("x".repeat(80), "\u0416".repeat(80), "🌌".repeat(80))) {
            assertEquals(name, BookmarkStore.validateName("  " + name + "  "));
            store.save(name, scene);
            assertTrue(new BookmarkStore(directory).load().contains(new BookmarkStore.Bookmark(name, scene)));
            store.save(name, scene.withColoring(new com.shangin.fractal.scene.ColoringSettings(PalettePreset.FIRE)));
            store.delete(name);
            assertTrue(store.load().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> BookmarkStore.validateName("🌌".repeat(81)));
        assertThrows(IllegalArgumentException.class, () -> BookmarkStore.validateName("\uD800"));
    }

    @Test void preservesLegacySingleFileNamesAlongsideNestedNames() throws Exception {
        String legacy = "\u0416".repeat(40);
        String filename = java.util.HexFormat.of().formatHex(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json";
        new LastSessionStore(directory.resolve(filename)).save(scene);
        var store = new BookmarkStore(directory);
        store.save("🌌".repeat(80), scene);
        assertEquals(2, store.load().size());
        assertTrue(store.load().contains(new BookmarkStore.Bookmark(legacy, scene)));
        store.save(legacy, scene);
        assertTrue(Files.exists(directory.resolve(filename)));
        store.delete(legacy);
        assertEquals(1, store.load().size());
    }
}
