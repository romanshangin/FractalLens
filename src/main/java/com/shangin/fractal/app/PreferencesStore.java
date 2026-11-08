package com.shangin.fractal.app;

import com.shangin.fractal.scene.WidePreviewSettings;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Application preferences that are not part of a scene, stored as a small properties
 * file beside the last session. Only explicit choices are written, so an absent value
 * keeps following the application default.
 */
public final class PreferencesStore {
    static final String FILE_NAME = "preferences.properties";
    static final String WIDE_PREVIEW = "widePreview";
    private static final long MAX_BYTES = 65_536;
    private final Path file;

    public PreferencesStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** The preferences that belong to the same application data directory as {@code session}. */
    public static PreferencesStore besides(LastSessionStore session) {
        return new PreferencesStore(session.path().resolveSibling(FILE_NAME));
    }

    /**
     * The explicit wide-preview choice; empty when none was saved or the contents are damaged.
     *
     * @throws IOException if the file cannot be read; the caller then applies the application default
     */
    public Optional<Boolean> widePreview() throws IOException {
        String value = read().getProperty(WIDE_PREVIEW);
        if ("on".equals(value)) return Optional.of(true);
        if ("off".equals(value)) return Optional.of(false);
        return Optional.empty();
    }

    /** Saves an explicit wide-preview choice, keeping other stored preferences of a readable file. */
    public void saveWidePreview(boolean enabled) throws IOException {
        Properties properties = readForReplacement();
        properties.setProperty(WIDE_PREVIEW, enabled ? "on" : "off");
        StringWriter text = new StringWriter();
        properties.store(text, "FractalLens preferences");
        LastSessionStore.atomicWrite(file, text.toString());
    }

    /** Restores each choice independently; old files retain the current size and quality defaults. */
    public WidePreviewSettings widePreviewSettings() throws IOException {
        Properties properties = read();
        WidePreviewSettings defaults = WidePreviewSettings.DEFAULT;
        String enabled = properties.getProperty(WIDE_PREVIEW);
        double extent = defaults.extent();
        int step = defaults.pixelStep();
        try {
            double saved = Double.parseDouble(properties.getProperty("widePreviewExtent", ""));
            if (saved == 1.5 || saved == 2.0 || saved == 3.0 || saved == 4.0) extent = saved;
        } catch (NumberFormatException ignored) {
            // Missing or damaged choices keep the application default.
        }
        try {
            int saved = Integer.parseInt(properties.getProperty("widePreviewPixelStep", ""));
            if (saved == 16 || saved == 8 || saved == 4 || saved == 2) step = saved;
        } catch (NumberFormatException ignored) {
            // Missing or damaged choices keep the application default.
        }
        return new WidePreviewSettings("on".equals(enabled) || (!"off".equals(enabled) && defaults.enabled()),
                extent, step, defaults.sampleLimit(), defaults.workMillis());
    }

    /** Saves all navigation choices together, including size and quality while disabled. */
    public void saveWidePreview(WidePreviewSettings settings) throws IOException {
        Properties properties = readForReplacement();
        properties.setProperty(WIDE_PREVIEW, settings.enabled() ? "on" : "off");
        properties.setProperty("widePreviewExtent", Double.toString(settings.extent()));
        properties.setProperty("widePreviewPixelStep", Integer.toString(settings.pixelStep()));
        StringWriter text = new StringWriter();
        properties.store(text, "FractalLens preferences");
        LastSessionStore.atomicWrite(file, text.toString());
    }

    private Properties readForReplacement() {
        try {
            return read();
        } catch (IOException unreadable) {
            // Atomic replacement needs only the directory, so a file that cannot be read must not lose the choice.
            return new Properties();
        }
    }

    private Properties read() throws IOException {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return properties;
        try {
            properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (CharacterCodingException | IllegalArgumentException malformed) {
            // A damaged file falls back to defaults; the next explicit choice replaces it.
            return new Properties();
        }
        return properties;
    }
}
