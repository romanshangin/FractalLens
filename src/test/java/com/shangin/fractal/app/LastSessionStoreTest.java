package com.shangin.fractal.app;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.FractalSceneJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LastSessionStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultLocationUsesThePublicApplicationNameOnEveryPlatform() {
        String originalOs = System.getProperty("os.name");
        try {
            for (String os : new String[] {"Mac OS X", "Windows 11", "Linux"}) {
                System.setProperty("os.name", os);
                Path path = LastSessionStore.defaultPath();
                assertEquals("last-session.json", path.getFileName().toString());
                assertEquals(os.equals("Linux") ? "fractallens" : "FractalLens",
                        path.getParent().getFileName().toString());
            }
        } finally {
            if (originalOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", originalOs);
        }
    }

    @Test
    void savesAndLoadsTheLatestScene() throws Exception {
        LastSessionStore store = store();
        FractalScene scene = scene(FractalPreset.JULIA, "-0.12", "0.34", "5E-80");

        store.save(scene);

        assertEquals(scene, store.load().orElseThrow());
    }

    @Test
    void ignoresAnInterruptedTemporaryGeneration() throws Exception {
        Path target = temporaryDirectory.resolve("last-session.json");
        LastSessionStore store = new LastSessionStore(target);
        FractalScene published = scene(FractalPreset.MANDELBROT, "-0.5", "0", "0.001");
        FractalScene interrupted = scene(FractalPreset.JULIA, "0.1", "-0.2", "1E-30");
        store.save(published);
        Files.writeString(target.resolveSibling("last-session.json123.tmp"),
                FractalSceneJson.write(interrupted));

        assertEquals(published, store.load().orElseThrow());
    }

    @Test
    void recoversThePreviousValidGenerationWhenThePrimaryIsMalformed() throws Exception {
        Path target = temporaryDirectory.resolve("last-session.json");
        LastSessionStore store = new LastSessionStore(target);
        FractalScene previous = scene(FractalPreset.TRICORN, "0.2", "0.3", "0.4");
        store.save(previous);
        store.save(scene(FractalPreset.JULIA, "-0.1", "0.7", "0.02"));
        Files.writeString(target, "{\"schemaVersion\": 2,");

        assertEquals(previous, store.load().orElseThrow());
    }

    @Test
    void recoversTheBackupWhenThePrimaryIsNotValidUtf8() throws Exception {
        Path target = temporaryDirectory.resolve("last-session.json");
        LastSessionStore store = new LastSessionStore(target);
        FractalScene previous = scene(FractalPreset.TRICORN, "0.2", "0.3", "0.4");
        store.save(previous);
        store.save(scene(FractalPreset.JULIA, "-0.1", "0.7", "0.02"));
        Files.write(target, new byte[] {(byte) 0xC3, 0x28});

        assertEquals(previous, store.load().orElseThrow());
    }

    @Test
    void malformedInputWithoutABackupFallsBackToNoSession() throws Exception {
        Path target = temporaryDirectory.resolve("last-session.json");
        Files.writeString(target, "[]");

        assertFalse(new LastSessionStore(target).load().isPresent());
    }

    private LastSessionStore store() {
        return new LastSessionStore(temporaryDirectory.resolve("last-session.json"));
    }

    private static FractalScene scene(
            FractalPreset preset,
            String real,
            String imaginary,
            String scale
    ) {
        return FractalScene.create(preset, PalettePreset.ICE)
                .withViewport(new Viewport(real, imaginary, scale));
    }
}
