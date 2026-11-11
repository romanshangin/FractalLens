package com.shangin.fractal.app;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.math.Viewport;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * The last view of each fractal, stored beside the last session so that switching
 * fractals returns to where each one was left, also after a restart. Coordinates are
 * decimal strings written and read without rounding. Entries are independent: a
 * missing or damaged entry only makes that fractal start at its home view.
 */
public final class FractalViewStore {
    static final String FILE_NAME = "fractal-views.properties";
    private static final long MAX_BYTES = 1_048_576L;
    private final Path file;

    public FractalViewStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** The views that belong to the same application data directory as {@code session}. */
    public static FractalViewStore besides(LastSessionStore session) {
        return new FractalViewStore(session.path().resolveSibling(FILE_NAME));
    }

    /**
     * The valid saved views; empty when none were saved or the file is damaged.
     *
     * @throws IOException if the file cannot be read; the caller then uses home views
     */
    public Map<FractalPreset, Viewport> load() throws IOException {
        Map<FractalPreset, Viewport> views = new EnumMap<>(FractalPreset.class);
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return views;
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (CharacterCodingException | IllegalArgumentException malformed) {
            return views;
        }
        for (FractalPreset preset : FractalPreset.values()) {
            Viewport viewport = viewport(properties, preset);
            if (viewport != null) views.put(preset, viewport);
        }
        return views;
    }

    /** Atomically replaces the stored views with {@code views}. */
    public void save(Map<FractalPreset, Viewport> views) throws IOException {
        Objects.requireNonNull(views);
        Properties properties = new Properties();
        views.forEach((preset, viewport) -> {
            String prefix = preset.name() + '.';
            properties.setProperty(prefix + "centerReal", viewport.center().real().toString());
            properties.setProperty(prefix + "centerImaginary", viewport.center().imaginary().toString());
            properties.setProperty(prefix + "scale", viewport.scaleExact().toString());
        });
        StringWriter text = new StringWriter();
        properties.store(text, "FractalLens last view of each fractal");
        LastSessionStore.atomicWrite(file, text.toString());
    }

    private static Viewport viewport(Properties properties, FractalPreset preset) {
        String prefix = preset.name() + '.';
        try {
            BigDecimal real = new BigDecimal(required(properties, prefix + "centerReal"));
            BigDecimal imaginary = new BigDecimal(required(properties, prefix + "centerImaginary"));
            BigDecimal scale = new BigDecimal(required(properties, prefix + "scale"));
            return new Viewport(new PreciseComplex(real, imaginary), scale);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            // NumberFormatException, a non-positive scale or a missing component.
            return null;
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value.trim();
    }
}
