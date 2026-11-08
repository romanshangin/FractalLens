package com.shangin.fractal.app;

import com.shangin.fractal.scene.WidePreviewSettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreferencesStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void livesBesideTheSessionAndKeepsAnAbsentChoiceEmpty() throws Exception {
        var session = new LastSessionStore(temporaryDirectory.resolve("data").resolve("last-session.json"));
        var store = PreferencesStore.besides(session);
        assertEquals(Optional.empty(), store.widePreview(), "No file means the application default applies");
        store.saveWidePreview(true);
        Path file = temporaryDirectory.resolve("data").resolve(PreferencesStore.FILE_NAME);
        assertTrue(Files.isRegularFile(file));
        assertEquals(Optional.of(true), store.widePreview());
        assertEquals(Optional.of(true), new PreferencesStore(file).widePreview(), "The choice survives a restart");
        store.saveWidePreview(false);
        assertEquals(Optional.of(false), store.widePreview());
    }

    @Test
    void sizeAndQualitySurviveRestartAndBooleanChanges() throws Exception {
        Path file = temporaryDirectory.resolve(PreferencesStore.FILE_NAME);
        var store = new PreferencesStore(file);
        var settings = new WidePreviewSettings(false, 3.0, 4, 262_144, 2_000);
        store.saveWidePreview(settings);
        assertEquals(settings, new PreferencesStore(file).widePreviewSettings());
        store.saveWidePreview(true);
        assertEquals(new WidePreviewSettings(true, 3.0, 4, 262_144, 2_000), store.widePreviewSettings());
    }

    @Test
    void oldOrDamagedChoicesUseIndependentDefaults() throws Exception {
        Path file = temporaryDirectory.resolve(PreferencesStore.FILE_NAME);
        var store = new PreferencesStore(file);
        assertEquals(WidePreviewSettings.DEFAULT, store.widePreviewSettings());
        Files.writeString(file, "widePreview=off\n");
        assertEquals(WidePreviewSettings.DISABLED, store.widePreviewSettings());
        for (String invalid : new String[] {"NaN", "Infinity", "-1", "5", "text", ""}) {
            Files.writeString(file, "widePreview=off\nwidePreviewExtent=" + invalid + "\nwidePreviewPixelStep=4\n");
            assertEquals(new WidePreviewSettings(false, 2.0, 4, 262_144, 2_000), store.widePreviewSettings());
            Files.writeString(file, "widePreview=off\nwidePreviewExtent=3.0\nwidePreviewPixelStep=" + invalid + "\n");
            assertEquals(new WidePreviewSettings(false, 3.0, 8, 262_144, 2_000), store.widePreviewSettings());
        }
    }

    @Test
    void keepsUnrelatedPropertiesWhenSaving() throws Exception {
        Path file = temporaryDirectory.resolve(PreferencesStore.FILE_NAME);
        Files.writeString(file, "future=value\n", StandardCharsets.UTF_8);
        new PreferencesStore(file).saveWidePreview(true);
        var properties = new Properties();
        properties.load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        assertEquals("value", properties.getProperty("future"));
        assertEquals("on", properties.getProperty(PreferencesStore.WIDE_PREVIEW));
    }

    @Test
    void unknownMalformedOrOversizedFilesFallBackToTheDefault() throws Exception {
        Path file = temporaryDirectory.resolve(PreferencesStore.FILE_NAME);
        var store = new PreferencesStore(file);
        for (String contents : new String[] {"widePreview=maybe\n", "widePreview=\\u12\n", "widePreview=ON\n"}) {
            Files.writeString(file, contents, StandardCharsets.UTF_8);
            assertEquals(Optional.empty(), store.widePreview(), contents);
        }
        Files.write(file, new byte[] {(byte) 0xff, (byte) 0xfe, '=', 'x'});
        assertEquals(Optional.empty(), store.widePreview(), "Invalid UTF-8");
        Files.writeString(file, "widePreview=on\n#" + "x".repeat(70_000), StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), store.widePreview(), "Oversized file");
        store.saveWidePreview(false);
        assertEquals(Optional.of(false), store.widePreview(), "An explicit choice replaces a damaged file");
    }

    @Test
    void explicitChoiceReplacesAFileThatCannotBeRead() throws Exception {
        assumeTrue(temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "Needs POSIX file permissions");
        Path file = temporaryDirectory.resolve(PreferencesStore.FILE_NAME);
        Files.writeString(file, "widePreview=on\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of());
        assumeFalse(Files.isReadable(file), "Needs a user that file permissions apply to");
        var store = new PreferencesStore(file);
        assertThrows(IOException.class, store::widePreview, "The caller reports the failure and applies the default");
        store.saveWidePreview(false);
        assertEquals(Optional.of(false), store.widePreview());
    }
}
