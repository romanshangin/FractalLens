package com.shangin.fractal.ui;

import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;

import java.util.List;
import java.util.Objects;

/**
 * Application Settings window shared by the AppKit sheet and the JavaFX form.
 * Settings are grouped into sections of choice fields; Render Settings is the
 * only section so far.
 */
final class SettingsWindow {
    static final String TITLE = "Settings";
    static final String MENU_TITLE = "Settings…";
    static final String HINT = "Changes apply to the current scene and are saved with it. "
            + "PNG export always uses adaptive antialiasing.";
    static final String RESET_TITLE = "Reset to Defaults";
    static final String RENDER_SECTION = "Render Settings";
    static final String STANDARD_ID = "standard-zoom-mode";
    static final String DEEP_ID = "deep-zoom-mode";
    /** Display order, from the least to the most antialiased presentation. */
    private static final List<InteractiveRenderMode> MODES = List.of(
            InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.FAST, InteractiveRenderMode.REFINED);
    private static final List<String> MODE_TITLES = MODES.stream().map(InteractiveRenderMode::toString).toList();

    /** One exclusive choice; {@code selected} indexes {@code choices}. */
    record ChoiceField(String section, String id, String label, String hint, List<String> choices, int selected) {
        ChoiceField {
            Objects.requireNonNull(section);
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(hint);
            choices = List.copyOf(choices);
            Objects.checkIndex(selected, choices.size());
        }
    }

    private SettingsWindow() {}

    static List<ChoiceField> fields(AntialiasSettings current) {
        return List.of(
                new ChoiceField(RENDER_SECTION, STANDARD_ID, "Standard zoom",
                        "Base only skips antialiasing. Base, then antialiasing smooths the visible frame "
                                + "afterwards. Antialiased display shows only finished antialiased tiles.",
                        MODE_TITLES, index(current.renderMode())),
                new ChoiceField(RENDER_SECTION, DEEP_ID, "Deep zoom",
                        "Used while Mandelbrot or Julia renders in deep zoom. Deep zoom antialiasing is "
                                + "precise and can take much longer than the base render.",
                        MODE_TITLES, index(current.deepZoomRenderMode())));
    }

    /** Selected index of every field, in {@link #fields} order, for the default settings. */
    static int[] defaultSelection() {
        return fields(new AntialiasSettings()).stream().mapToInt(ChoiceField::selected).toArray();
    }

    /** Maps the selected index of every field, in {@link #fields} order, to settings. */
    static AntialiasSettings selection(int... selected) {
        if (selected.length != 2) {
            throw new IllegalArgumentException("Expected 2 settings, got " + selected.length);
        }
        return new AntialiasSettings(MODES.get(selected[0]), MODES.get(selected[1]));
    }

    /** Position of {@code mode} in the mode drop-down lists. */
    static int index(InteractiveRenderMode mode) {
        return MODES.indexOf(Objects.requireNonNull(mode));
    }
}
