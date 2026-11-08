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
            assertEquals(List.of(SettingsWindow.STANDARD_ID, SettingsWindow.DEEP_ID, SettingsWindow.WIDE_PREVIEW_ID),
                    fields.stream().map(SettingsWindow.ChoiceField::id).toList());
            assertEquals(List.of(SettingsWindow.RENDER_SECTION, SettingsWindow.RENDER_SECTION,
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
                SettingsWindow.index(InteractiveRenderMode.BASE_ONLY), wideDefault}, SettingsWindow.defaultSelection());
        assertEquals(new SettingsWindow.Values(new AntialiasSettings(), WidePreviewSettings.DEFAULT.enabled()),
                SettingsWindow.Values.defaults());
    }

    @Test
    void rejectsIncompleteOrOutOfRangeSelections() {
        assertThrows(IllegalArgumentException.class, () -> SettingsWindow.selection(0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> SettingsWindow.selection(0, 0, 2));
    }
}
