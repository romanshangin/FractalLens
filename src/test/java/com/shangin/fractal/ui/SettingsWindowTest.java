package com.shangin.fractal.ui;

import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.WidePreviewSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsWindowTest {

    @Test
    void navigationSectionFollowsRenderSettingsAndRoundTripsTheWidePreviewChoice() {
        for (boolean wide : List.of(false, true)) {
            var current = new SettingsWindow.Values(
                    new AntialiasSettings(InteractiveRenderMode.FAST, InteractiveRenderMode.REFINED), wide);
            var fields = SettingsWindow.fields(current);
            assertEquals(List.of(SettingsWindow.STANDARD_ID, SettingsWindow.DEEP_ID, SettingsWindow.WIDE_PREVIEW_ID,
                    SettingsWindow.WIDE_PREVIEW_EXTENT_ID, SettingsWindow.WIDE_PREVIEW_QUALITY_ID),
                    fields.stream().map(SettingsWindow.ChoiceField::id).toList());
            assertEquals(List.of(SettingsWindow.RENDER_SECTION, SettingsWindow.RENDER_SECTION,
                    SettingsWindow.NAVIGATION_SECTION, SettingsWindow.NAVIGATION_SECTION,
                    SettingsWindow.NAVIGATION_SECTION), fields.stream().map(SettingsWindow.ChoiceField::section).toList());
            assertEquals(List.of("Off", "On"), fields.get(2).choices());
            assertEquals(current, SettingsWindow.selection(
                    fields.stream().mapToInt(SettingsWindow.ChoiceField::selected).toArray()));
        }
    }

    @Test
    void defaultsUseTheApplicationWidePreviewDefault() {
        int wideDefault = WidePreviewSettings.DEFAULT.enabled() ? 1 : 0;
        assertArrayEquals(new int[] {SettingsWindow.index(InteractiveRenderMode.REFINED),
                SettingsWindow.index(InteractiveRenderMode.BASE_ONLY), wideDefault, 1, 1}, SettingsWindow.defaultSelection());
        assertEquals(new SettingsWindow.Values(new AntialiasSettings(), WidePreviewSettings.DEFAULT.enabled()),
                SettingsWindow.Values.defaults());
    }

    @Test
    void allSizesAndQualitiesRoundTripIncludingWhileDisabled() {
        for (boolean enabled : List.of(false, true)) {
            for (double extent : List.of(1.5, 2.0, 3.0, 4.0)) {
                for (int step : List.of(16, 8, 4, 2)) {
                    var current = new SettingsWindow.Values(new AntialiasSettings(), enabled, extent, step);
                    var restored = SettingsWindow.selection(SettingsWindow.fields(current).stream()
                            .mapToInt(SettingsWindow.ChoiceField::selected).toArray());
                    assertEquals(current, restored);
                    assertEquals(new WidePreviewSettings(enabled, extent, step, 262_144, 2_000),
                            restored.previewSettings());
                }
            }
        }
    }

    @Test
    void rejectsIncompleteOrOutOfRangeSelections() {
        assertThrows(IllegalArgumentException.class, () -> SettingsWindow.selection(0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> SettingsWindow.selection(0, 0, 2, 1, 1));
    }
}
