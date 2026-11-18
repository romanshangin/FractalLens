package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneGalleryTest {
    private static final String ZOOM_REAL = "-0.743643887037158704752191506114774";
    private static final String ZOOM_IMAGINARY = "0.131825904205311970493132056385139";

    private static String catalog(String... entries) {
        return "{\"catalogVersion\": 1, \"entries\": [" + String.join(",", entries) + "]}";
    }

    private static String entry(String id, String extra) {
        return "{\"id\": \"" + id + "\", \"name\": \"Name " + id + "\", \"description\": \"Description\","
                + " \"fractal\": \"MANDELBROT\","
                + " \"viewport\": {\"centerReal\": \"-0.75\", \"centerImaginary\": \"0.1\", \"scale\": \"0.15\"},"
                + " \"coloring\": {\"palette\": \"OCEAN\"}" + extra + "}";
    }

    @Test
    void bundledCatalogIsValidAndGroupedByEveryFractal() throws IOException {
        SceneGallery.Catalog catalog = SceneGallery.loadBundled();
        assertEquals(SceneGallery.CURRENT_CATALOG_VERSION, catalog.version());
        assertEquals(List.of(), catalog.problems());
        Map<FractalPreset, List<SceneGallery.Entry>> groups = catalog.groups();
        assertEquals(List.of(FractalPreset.values()), List.copyOf(groups.keySet()));
        groups.forEach((fractal, entries) -> {
            assertTrue(entries.size() >= 3, fractal.name());
            entries.forEach(entry -> assertEquals(fractal, entry.scene().fractal()));
        });
        var ids = new HashSet<String>();
        var names = new HashSet<String>();
        for (var entry : catalog.entries()) {
            assertTrue(ids.add(entry.id()), entry.id());
            assertTrue(names.add(entry.name()), entry.name());
            assertEquals(new AntialiasSettings(), entry.scene().antialiasing());
        }
    }

    @Test
    void bundledEntriesKeepExactCoordinatesAndJuliaConstants() throws IOException {
        var entries = SceneGallery.loadBundled().entries();
        var zoom = entries.stream().filter(entry -> entry.id().equals("mandelbrot-zoom-sequence")).findFirst().orElseThrow();
        assertEquals(new Viewport(ZOOM_REAL, ZOOM_IMAGINARY, "1e-10"), zoom.scene().viewport());
        // All 33 published digits survive; nothing is rounded through binary64.
        assertEquals(ZOOM_REAL, zoom.scene().viewport().center().real().toPlainString());
        assertEquals(ZOOM_IMAGINARY, zoom.scene().viewport().center().imaginary().toPlainString());
        assertEquals(new IterationSettings(1000, 100), zoom.scene().iterations());

        var rabbit = entries.stream().filter(entry -> entry.id().equals("julia-douady-rabbit")).findFirst().orElseThrow();
        assertEquals(new JuliaParameters(-0.12256116687665, 0.74486176661974), rabbit.scene().juliaParameters());
        // Non-Julia entries keep the default constant so they round-trip like any other scene.
        entries.stream().filter(entry -> entry.scene().fractal() != FractalPreset.JULIA)
                .forEach(entry -> assertEquals(new JuliaParameters(), entry.scene().juliaParameters()));
        // Every entry survives the scene file format used by bookmarks and sessions.
        for (var entry : entries) {
            assertEquals(entry.scene(), FractalSceneJson.read(FractalSceneJson.write(entry.scene())), entry.id());
        }
    }

    @Test
    void entryAppliesTheViewersAntialiasingModes() throws IOException {
        var entry = SceneGallery.loadBundled().entries().getFirst();
        var viewer = new AntialiasSettings(InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.FAST);
        FractalScene scene = entry.sceneWith(viewer);
        assertEquals(viewer, scene.antialiasing());
        assertEquals(entry.scene().viewport(), scene.viewport());
        assertEquals(entry.scene().coloring(), scene.coloring());
    }

    @Test
    void optionalFieldsDefaultAndExplicitFieldsAreRead() {
        var catalog = SceneGallery.read(catalog(
                entry("defaults", ""),
                "{\"id\": \"explicit\", \"name\": \" Explicit \", \"description\": \"D\", \"fractal\": \"JULIA\","
                        + " \"viewport\": {\"centerReal\": \"0.1\", \"centerImaginary\": \"-0.2\", \"scale\": \"0.5\"},"
                        + " \"juliaParameters\": {\"real\": -0.4, \"imaginary\": 0.6},"
                        + " \"iterations\": {\"baseIterations\": 700, \"iterationsPerZoomLevel\": 0},"
                        + " \"coloring\": {\"palette\": \"FIRE\", \"colorScale\": 2.5, \"offset\": 0.25,"
                        + " \"histogramColoring\": true, \"orbitTrap\": \"POINT\"}}"));
        assertEquals(List.of(), catalog.problems());
        FractalScene defaults = catalog.entries().get(0).scene();
        assertEquals(new IterationSettings(), defaults.iterations());
        assertEquals(new ColoringSettings(PalettePreset.OCEAN), defaults.coloring());
        var explicit = catalog.entries().get(1);
        assertEquals("Explicit", explicit.name());
        assertEquals(new JuliaParameters(-0.4, 0.6), explicit.scene().juliaParameters());
        assertEquals(new IterationSettings(700, 0), explicit.scene().iterations());
        assertEquals(new ColoringSettings(PalettePreset.FIRE, PalettePreset.FIRE.stops(), 2.5, 0.25, true,
                OrbitTrap.POINT), explicit.scene().coloring());
    }

    @Test
    void invalidEntriesAreReportedWithoutHidingValidOnes() {
        var catalog = SceneGallery.read(catalog(
                entry("valid", ""),
                entry("valid", ""),
                entry("Bad_Id", ""),
                entry("unknown-field", ", \"zoom\": 3"),
                entry("non-julia-constant", ", \"juliaParameters\": {\"real\": 0, \"imaginary\": 1}"),
                entry("bad-iterations", ", \"iterations\": {\"baseIterations\": 0, \"iterationsPerZoomLevel\": 1}"),
                "{\"id\": \"missing-coloring\", \"name\": \"N\", \"description\": \"D\", \"fractal\": \"TRICORN\","
                        + " \"viewport\": {\"centerReal\": \"0\", \"centerImaginary\": \"0\", \"scale\": \"1\"}}",
                "{\"id\": \"bad-palette\", \"name\": \"N\", \"description\": \"D\", \"fractal\": \"TRICORN\","
                        + " \"viewport\": {\"centerReal\": \"0\", \"centerImaginary\": \"0\", \"scale\": \"1\"},"
                        + " \"coloring\": {\"palette\": \"NEON\"}}",
                "{\"id\": \"julia-without-constant\", \"name\": \"N\", \"description\": \"D\", \"fractal\": \"JULIA\","
                        + " \"viewport\": {\"centerReal\": \"0\", \"centerImaginary\": \"0\", \"scale\": \"1\"},"
                        + " \"coloring\": {\"palette\": \"ICE\"}}",
                "{\"id\": \"zero-scale\", \"name\": \"N\", \"description\": \"D\", \"fractal\": \"TRICORN\","
                        + " \"viewport\": {\"centerReal\": \"0\", \"centerImaginary\": \"0\", \"scale\": \"0\"},"
                        + " \"coloring\": {\"palette\": \"ICE\"}}",
                "{\"id\": \"inexact-viewport\", \"name\": \"N\", \"description\": \"D\", \"fractal\": \"TRICORN\","
                        + " \"viewport\": {\"centerReal\": 0.1, \"centerImaginary\": \"0\", \"scale\": \"1\"},"
                        + " \"coloring\": {\"palette\": \"ICE\"}}",
                "{\"id\": \"blank-name\", \"name\": \"  \", \"description\": \"D\", \"fractal\": \"TRICORN\","
                        + " \"viewport\": {\"centerReal\": \"0\", \"centerImaginary\": \"0\", \"scale\": \"1\"},"
                        + " \"coloring\": {\"palette\": \"ICE\"}}",
                "\"not an object\""));
        assertEquals(List.of("valid"), catalog.entries().stream().map(SceneGallery.Entry::id).toList());
        assertEquals(12, catalog.problems().size(), catalog.problems().toString());
        assertTrue(catalog.problems().getFirst().startsWith("Entry 2: Duplicate id"), catalog.problems().getFirst());
        assertTrue(catalog.problems().stream().anyMatch(problem -> problem.contains("Unknown entry field: zoom")));
        assertTrue(catalog.problems().stream().anyMatch(problem -> problem.contains("only to JULIA")));
    }

    @Test
    void invalidCatalogsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SceneGallery.read("{"));
        assertThrows(IllegalArgumentException.class, () -> SceneGallery.read("[]"));
        assertThrows(IllegalArgumentException.class, () -> SceneGallery.read("{\"entries\": []}"));
        var unsupported = assertThrows(IllegalArgumentException.class,
                () -> SceneGallery.read("{\"catalogVersion\": 2, \"entries\": []}"));
        assertTrue(unsupported.getMessage().contains("Unsupported gallery catalog version: 2"));
        assertThrows(IllegalArgumentException.class, () -> SceneGallery.read("{\"catalogVersion\": 1}"));
        assertThrows(IllegalArgumentException.class,
                () -> SceneGallery.read("{\"catalogVersion\": 1, \"entries\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> SceneGallery.read(" ".repeat(1_048_577)));
        assertFalse(SceneGallery.read(catalog()).problems().iterator().hasNext());
    }
}
