package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static com.shangin.fractal.scene.FractalSceneJson.array;
import static com.shangin.fractal.scene.FractalSceneJson.bool;
import static com.shangin.fractal.scene.FractalSceneJson.decimalNumber;
import static com.shangin.fractal.scene.FractalSceneJson.enumValue;
import static com.shangin.fractal.scene.FractalSceneJson.integer;
import static com.shangin.fractal.scene.FractalSceneJson.object;
import static com.shangin.fractal.scene.FractalSceneJson.required;
import static com.shangin.fractal.scene.FractalSceneJson.string;

/**
 * Bundled, versioned catalog of curated scenes ("interesting places").
 *
 * <p>The catalog is read-only application data. It never reads or writes user
 * bookmarks, so catalog updates cannot change or remove them. Each entry fixes the
 * fractal, exact decimal viewport, Julia constant, iteration settings and coloring;
 * the viewer's antialiasing modes are not part of an entry. The format is described
 * in {@code docs/architecture/SCENE_GALLERY.md}.
 */
public final class SceneGallery {
    public static final int CURRENT_CATALOG_VERSION = 1;
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_DESCRIPTION_LENGTH = 400;
    static final String RESOURCE = "gallery-catalog.json";
    private static final int MAX_CATALOG_LENGTH = 1_048_576;
    private static final int MAX_ENTRIES = 1_000;
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int MAX_ID_LENGTH = 64;
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "id", "name", "description", "fractal", "viewport", "juliaParameters", "iterations", "coloring");
    private static final Set<String> VIEWPORT_FIELDS = Set.of("centerReal", "centerImaginary", "scale");
    private static final Set<String> JULIA_FIELDS = Set.of("real", "imaginary");
    private static final Set<String> ITERATION_FIELDS = Set.of("baseIterations", "iterationsPerZoomLevel");
    private static final Set<String> COLORING_FIELDS = Set.of(
            "palette", "colorScale", "offset", "histogramColoring", "orbitTrap");

    private SceneGallery() {}

    /** One curated place. The scene carries default antialiasing; callers apply their own modes. */
    public record Entry(String id, String name, String description, FractalScene scene) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(description);
            Objects.requireNonNull(scene);
        }

        /** The entry scene presented with the viewer's current antialiasing modes. */
        public FractalScene sceneWith(AntialiasSettings antialiasing) {
            return scene.withAntialiasing(Objects.requireNonNull(antialiasing));
        }
    }

    /**
     * A parsed catalog. Entries that fail validation are left out and described in
     * {@code problems}, so one bad entry does not hide the rest of the gallery.
     */
    public record Catalog(int version, List<Entry> entries, List<String> problems) {
        public Catalog {
            entries = List.copyOf(entries);
            problems = List.copyOf(problems);
        }

        /** Valid entries grouped by fractal, in fractal menu order; empty groups are omitted. */
        public Map<FractalPreset, List<Entry>> groups() {
            Map<FractalPreset, List<Entry>> groups = new EnumMap<>(FractalPreset.class);
            for (Entry entry : entries) {
                groups.computeIfAbsent(entry.scene().fractal(), ignored -> new ArrayList<>()).add(entry);
            }
            groups.replaceAll((fractal, list) -> List.copyOf(list));
            return groups;
        }
    }

    /** Loads the catalog shipped with the application. */
    public static Catalog loadBundled() throws IOException {
        try (InputStream input = SceneGallery.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("The bundled gallery catalog is missing");
            byte[] bytes = input.readNBytes(MAX_CATALOG_LENGTH + 1);
            if (bytes.length > MAX_CATALOG_LENGTH) {
                throw new IOException("The bundled gallery catalog exceeds the 1 MiB limit");
            }
            return read(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The bundled gallery catalog is invalid: " + invalid.getMessage(), invalid);
        }
    }

    /**
     * Parses catalog JSON.
     *
     * @throws IllegalArgumentException if the document, its version or its entry list is invalid
     */
    public static Catalog read(String json) {
        Objects.requireNonNull(json, "JSON must not be null");
        if (json.length() > MAX_CATALOG_LENGTH) {
            throw new IllegalArgumentException("Gallery catalog exceeds the 1 MiB limit");
        }
        Map<String, Object> root;
        int version;
        List<Object> values;
        try {
            root = object(FractalSceneJson.parse(json), "catalog");
            version = integer(required(root, "catalogVersion"), "catalogVersion");
            if (version != CURRENT_CATALOG_VERSION) {
                throw new IllegalArgumentException("Unsupported gallery catalog version: " + version);
            }
            values = array(required(root, "entries"), "entries");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid gallery catalog", exception);
        }
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Gallery entry count exceeds the supported limit");
        }
        List<Entry> entries = new ArrayList<>(values.size());
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            try {
                Entry entry = entry(object(values.get(index), "entry"));
                if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate id: " + entry.id());
                entries.add(entry);
            } catch (RuntimeException invalid) {
                problems.add("Entry " + (index + 1) + ": " + invalid.getMessage());
            }
        }
        return new Catalog(version, entries, problems);
    }

    private static Entry entry(Map<String, Object> value) {
        rejectUnknown(value, ENTRY_FIELDS, "entry");
        String id = string(required(value, "id"), "id");
        if (id.length() > MAX_ID_LENGTH || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be 1 to " + MAX_ID_LENGTH
                    + " lowercase letters, digits and single hyphens");
        }
        String name = text(value, "name", MAX_NAME_LENGTH);
        String description = text(value, "description", MAX_DESCRIPTION_LENGTH);
        FractalPreset fractal = enumValue(FractalPreset.class, string(required(value, "fractal"), "fractal"), "fractal");
        rejectUnknown(object(required(value, "viewport"), "viewport"), VIEWPORT_FIELDS, "viewport");

        JuliaParameters julia = new JuliaParameters();
        if (fractal == FractalPreset.JULIA) {
            Map<String, Object> parameters = object(required(value, "juliaParameters"), "juliaParameters");
            rejectUnknown(parameters, JULIA_FIELDS, "juliaParameters");
            julia = new JuliaParameters(
                    decimalNumber(required(parameters, "real"), "julia real"),
                    decimalNumber(required(parameters, "imaginary"), "julia imaginary"));
        } else if (value.containsKey("juliaParameters")) {
            throw new IllegalArgumentException("juliaParameters apply only to JULIA entries");
        }

        IterationSettings iterations = new IterationSettings();
        if (value.containsKey("iterations")) {
            Map<String, Object> object = object(value.get("iterations"), "iterations");
            rejectUnknown(object, ITERATION_FIELDS, "iterations");
            iterations = new IterationSettings(
                    integer(required(object, "baseIterations"), "baseIterations"),
                    integer(required(object, "iterationsPerZoomLevel"), "iterationsPerZoomLevel"));
        }

        Map<String, Object> coloring = object(required(value, "coloring"), "coloring");
        rejectUnknown(coloring, COLORING_FIELDS, "coloring");
        PalettePreset palette = enumValue(PalettePreset.class,
                string(required(coloring, "palette"), "palette"), "palette");
        ColoringSettings colors = new ColoringSettings(
                palette,
                palette.stops(),
                coloring.containsKey("colorScale")
                        ? decimalNumber(coloring.get("colorScale"), "colorScale")
                        : ColoringSettings.DEFAULT_COLOR_SCALE,
                coloring.containsKey("offset") ? decimalNumber(coloring.get("offset"), "offset") : 0.0,
                coloring.containsKey("histogramColoring")
                        && bool(coloring.get("histogramColoring"), "histogramColoring"),
                coloring.containsKey("orbitTrap")
                        ? enumValue(OrbitTrap.class, string(coloring.get("orbitTrap"), "orbitTrap"), "orbitTrap")
                        : OrbitTrap.NONE);

        FractalScene scene = new FractalScene(fractal, FractalSceneJson.viewport(value), iterations, colors,
                new AntialiasSettings(), julia);
        return new Entry(id, name, description, scene);
    }

    private static String text(Map<String, Object> value, String field, int maxLength) {
        String text = string(required(value, field), field).strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > maxLength
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be 1 to " + maxLength
                    + " characters with no control characters");
        }
        return text;
    }

    private static void rejectUnknown(Map<String, Object> object, Set<String> allowed, String name) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown " + name + " field: " + key);
        }
    }
}
