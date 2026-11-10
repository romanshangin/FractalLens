package com.shangin.fractal.ui;

import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.WidePreviewSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsWindowTest {

    private static SettingsWindow.Values restore(SettingsWindow.Values current) {
        var fields = SettingsWindow.fields(current);
        return SettingsWindow.selection(
                SettingsWindow.choiceFields(fields).stream().mapToInt(SettingsWindow.ChoiceField::selected).toArray(),
                SettingsWindow.textFields(fields).stream().map(SettingsWindow.TextField::text).toList());
    }

    @Test
    void renderSettingsStartWithIterationsAndNavigationRoundTripsTheWidePreviewChoice() {
        for (boolean wide : List.of(false, true)) {
            var current = new SettingsWindow.Values(new IterationSettings(420, 70),
                    new AntialiasSettings(InteractiveRenderMode.FAST, InteractiveRenderMode.REFINED), wide);
            var fields = SettingsWindow.fields(current);
            assertEquals(List.of(SettingsWindow.BASE_ITERATIONS_ID, SettingsWindow.ZOOM_ITERATIONS_ID,
                    SettingsWindow.STANDARD_ID, SettingsWindow.DEEP_ID, SettingsWindow.WIDE_PREVIEW_ID,
                    SettingsWindow.WIDE_PREVIEW_EXTENT_ID, SettingsWindow.WIDE_PREVIEW_QUALITY_ID),
                    fields.stream().map(SettingsWindow.Field::id).toList());
            assertEquals(List.of(SettingsWindow.RENDER_SECTION, SettingsWindow.RENDER_SECTION,
                    SettingsWindow.RENDER_SECTION, SettingsWindow.RENDER_SECTION,
                    SettingsWindow.NAVIGATION_SECTION, SettingsWindow.NAVIGATION_SECTION,
                    SettingsWindow.NAVIGATION_SECTION), fields.stream().map(SettingsWindow.Field::section).toList());
            assertEquals(List.of("420", "70"),
                    SettingsWindow.textFields(fields).stream().map(SettingsWindow.TextField::text).toList());
            assertEquals(List.of("Off", "On"), SettingsWindow.choiceFields(fields).get(2).choices());
            assertEquals(current, restore(current));
        }
    }

    @Test
    void defaultsUseTheApplicationWidePreviewAndIterationDefaults() {
        int wideDefault = WidePreviewSettings.DEFAULT.enabled() ? 1 : 0;
        assertArrayEquals(new int[] {SettingsWindow.index(InteractiveRenderMode.REFINED),
                SettingsWindow.index(InteractiveRenderMode.BASE_ONLY), wideDefault, 1, 1}, SettingsWindow.defaultSelection());
        assertEquals(List.of(Integer.toString(IterationSettings.DEFAULT_BASE_ITERATIONS),
                Integer.toString(IterationSettings.DEFAULT_ITERATIONS_PER_ZOOM_LEVEL)), SettingsWindow.defaultTexts());
        assertEquals(new SettingsWindow.Values(new IterationSettings(), new AntialiasSettings(),
                WidePreviewSettings.DEFAULT.enabled()), SettingsWindow.Values.defaults());
        assertEquals(SettingsWindow.defaultTexts(), SettingsWindow.textFields(SettingsWindow.fields(
                new SettingsWindow.Values(new IterationSettings(420, 70), new AntialiasSettings(), false))).stream()
                .map(SettingsWindow.TextField::defaultText).toList());
    }

    @Test
    void allSizesAndQualitiesRoundTripIncludingWhileDisabled() {
        for (boolean enabled : List.of(false, true)) {
            for (double extent : List.of(1.5, 2.0, 3.0, 4.0)) {
                for (int step : List.of(16, 8, 4, 2)) {
                    var current = new SettingsWindow.Values(new IterationSettings(), new AntialiasSettings(),
                            enabled, extent, step);
                    var restored = restore(current);
                    assertEquals(current, restored);
                    assertEquals(new WidePreviewSettings(enabled, extent, step, WidePreviewSettings.DEFAULT.sampleLimit(), 2_000),
                            restored.previewSettings());
                }
            }
        }
    }

    @Test
    void iterationDraftsAcceptTrimmedWholeNumbersAndRejectEverythingElse() {
        int[] choices = SettingsWindow.defaultSelection();
        assertEquals(new IterationSettings(420, 0),
                SettingsWindow.selection(choices, List.of(" +420 ", "0")).iterations());
        assertEquals(0, SettingsWindow.invalidText(List.of("1", "0")));
        for (String base : List.of("", "0", "-1", "-0", "1.5", "1e3", "abc", "99999999999")) {
            assertEquals(1, SettingsWindow.invalidText(List.of(base, "50")), base);
            assertThrows(IllegalArgumentException.class, () -> SettingsWindow.selection(choices, List.of(base, "50")));
        }
        for (String zoom : List.of("", "-1", "-0", "2.5", "x", "99999999999")) {
            assertEquals(2, SettingsWindow.invalidText(List.of("300", zoom)), zoom);
        }
        assertEquals(1, SettingsWindow.invalidText(List.of("x", "y")), "The first invalid field is reported");
    }

    @Test
    void rejectsIncompleteOrOutOfRangeSelections() {
        List<String> texts = SettingsWindow.defaultTexts();
        assertThrows(IllegalArgumentException.class, () -> SettingsWindow.selection(new int[] {0, 0}, texts));
        assertThrows(IndexOutOfBoundsException.class,
                () -> SettingsWindow.selection(new int[] {0, 0, 2, 1, 1}, texts));
        assertThrows(IllegalArgumentException.class,
                () -> SettingsWindow.selection(SettingsWindow.defaultSelection(), List.of("300")));
    }
}
