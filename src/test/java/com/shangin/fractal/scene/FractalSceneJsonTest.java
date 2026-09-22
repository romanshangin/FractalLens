package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractalSceneJsonTest {

    @Test
    void roundTripsEverySceneSettingWithoutLosingDecimalCoordinates() {
        String real = "-0.743643887037151000000000000000000000000000000000000000000001";
        String imaginary = "0.131825904205330000000000000000000000000000000000000000000007";
        String scale = "1.2E-75";
        FractalScene scene = new FractalScene(
                FractalPreset.JULIA,
                new Viewport(real, imaginary, scale),
                new IterationSettings(725, 83),
                new ColoringSettings(
                        PalettePreset.PLASMA,
                        List.of(new ColorStop(0.0, 0xFF010203),
                                new ColorStop(0.375, 0x80112233),
                                new ColorStop(1.0, 0xFFFFFFFF)),
                        19.25,
                        -0.125,
                        true,
                        OrbitTrap.UNIT_CIRCLE),
                new AntialiasSettings(
                        SamplingPattern.DETERMINISTIC_JITTER,
                        InteractiveRenderMode.FAST));

        String json = FractalSceneJson.write(scene);
        FractalScene restored = FractalSceneJson.read(json);

        assertEquals(scene, restored);
        assertTrue(json.contains("\"centerReal\": \"" + real + "\""));
        assertTrue(json.contains("\"centerImaginary\": \"" + imaginary + "\""));
        assertTrue(json.contains("\"scale\": \"" + scale + "\""));
    }

    @Test
    void migratesVersionOneDefaultsToTheCurrentSceneModel() {
        String versionOne = """
                {
                  "schemaVersion": 1,
                  "fractal": "BURNING_SHIP",
                  "viewport": {
                    "centerReal": "-1.75",
                    "centerImaginary": "-0.04",
                    "scale": "0.00000000000000000009"
                  },
                  "iterations": {
                    "baseIterations": 410,
                    "iterationsPerZoomLevel": 60
                  },
                  "coloring": {
                    "palette": "FIRE",
                    "colorScale": 12.5,
                    "offset": 0.25
                  }
                }
                """;

        FractalScene migrated = FractalSceneJson.read(versionOne);

        assertEquals(FractalPreset.BURNING_SHIP, migrated.fractal());
        assertEquals(new Viewport("-1.75", "-0.04", "9E-20"), migrated.viewport());
        assertEquals(new IterationSettings(410, 60), migrated.iterations());
        assertEquals(PalettePreset.FIRE.stops(), migrated.coloring().paletteStops());
        assertEquals(false, migrated.coloring().histogramColoring());
        assertEquals(OrbitTrap.NONE, migrated.coloring().orbitTrap());
        assertEquals(new AntialiasSettings(), migrated.antialiasing());
        assertTrue(FractalSceneJson.write(migrated).contains("\"schemaVersion\": 2"));
    }

    @Test
    void rejectsMalformedAndUnsupportedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> FractalSceneJson.read("{not-json}"));
        assertThrows(IllegalArgumentException.class,
                () -> FractalSceneJson.read("{\"schemaVersion\":2,\"schemaVersion\":2}"));
        assertThrows(IllegalArgumentException.class,
                () -> FractalSceneJson.read("{\"schemaVersion\":99}"));
        assertThrows(IllegalArgumentException.class,
                () -> FractalSceneJson.read("{\"schemaVersion\":2,\"fractal\":\"UNKNOWN\"}"));
    }
}
