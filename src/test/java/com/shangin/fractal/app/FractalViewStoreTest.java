package com.shangin.fractal.app;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractalViewStoreTest {
    @TempDir
    Path directory;

    @Test
    void savedViewsRoundTripEveryDecimalDigit() throws Exception {
        var store = FractalViewStore.besides(new LastSessionStore(directory.resolve("last-session.json")));
        Map<FractalPreset, Viewport> views = new EnumMap<>(FractalPreset.class);
        views.put(FractalPreset.MANDELBROT, new Viewport(
                "-0.743643887037158704752191506114774123456789",
                "0.131825904205311970493132056385139987654321",
                "1.5E-38"));
        views.put(FractalPreset.BURNING_SHIP, new Viewport("-1.7615", "-0.0285", "0.012"));

        store.save(views);

        assertTrue(Files.isRegularFile(directory.resolve(FractalViewStore.FILE_NAME)));
        Map<FractalPreset, Viewport> loaded = store.load();
        assertEquals(views.keySet(), loaded.keySet());
        views.forEach((preset, viewport) -> {
            Viewport restored = loaded.get(preset);
            assertEquals(0, viewport.center().real().compareTo(restored.center().real()));
            assertEquals(0, viewport.center().imaginary().compareTo(restored.center().imaginary()));
            assertEquals(0, viewport.scaleExact().compareTo(restored.scaleExact()));
        });
    }

    @Test
    void missingFileHasNoViews() throws Exception {
        assertTrue(new FractalViewStore(directory.resolve("absent.properties")).load().isEmpty());
    }

    @Test
    void damagedEntriesAreSkippedIndependently() throws Exception {
        Path file = directory.resolve(FractalViewStore.FILE_NAME);
        Files.writeString(file, String.join("\n",
                "MANDELBROT.centerReal=-0.5",
                "MANDELBROT.centerImaginary=0.25",
                "MANDELBROT.scale=0.75",
                "JULIA.centerReal=not-a-number",
                "JULIA.centerImaginary=0",
                "JULIA.scale=1",
                "TRICORN.centerReal=0",
                "TRICORN.centerImaginary=0",
                "TRICORN.scale=0",
                "BURNING_SHIP.centerReal=0",
                "BURNING_SHIP.scale=1",
                "UNKNOWN.centerReal=0",
                "UNKNOWN.centerImaginary=0",
                "UNKNOWN.scale=1"), StandardCharsets.UTF_8);

        Map<FractalPreset, Viewport> loaded = new FractalViewStore(file).load();

        assertEquals(Map.of(FractalPreset.MANDELBROT, new Viewport("-0.5", "0.25", "0.75")), loaded);
    }

    @Test
    void oversizedOrMalformedFilesHaveNoViews() throws Exception {
        Path oversized = directory.resolve("oversized.properties");
        Files.writeString(oversized, "MANDELBROT.scale=" + "1".repeat(1_100_000), StandardCharsets.UTF_8);
        assertTrue(new FractalViewStore(oversized).load().isEmpty());

        Path malformed = directory.resolve("malformed.properties");
        Files.writeString(malformed, "MANDELBROT.centerReal=\\uZZZZ", StandardCharsets.UTF_8);
        assertTrue(new FractalViewStore(malformed).load().isEmpty());
    }

    @Test
    void savingReplacesPreviouslyStoredViews() throws Exception {
        var store = new FractalViewStore(directory.resolve(FractalViewStore.FILE_NAME));
        store.save(Map.of(FractalPreset.JULIA, new Viewport("0.1", "0.2", "0.3")));
        store.save(Map.of(FractalPreset.TRICORN, new Viewport("0", "0", "1")));

        assertEquals(Map.of(FractalPreset.TRICORN, new Viewport("0", "0", "1")), store.load());
    }
}
