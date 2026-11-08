package com.shangin.fractal.ui;

import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.WidePreviewSettings;

import java.util.List;
import java.util.Objects;

/**
 * Application Settings window shared by the AppKit sheet and the JavaFX form.
 * Settings are grouped into sections of choice fields. Render Settings belong to
 * the scene; Navigation is an application preference.
 */
final class SettingsWindow {
    static final String TITLE = "Settings";
    static final String MENU_TITLE = "Settings…";
    static final String HINT = "Render settings apply to the current scene and are saved with it. "
            + "Navigation settings apply to the application. PNG export always uses adaptive antialiasing.";
    static final String RESET_TITLE = "Reset to Defaults";
    static final String RENDER_SECTION = "Render Settings";
    static final String STANDARD_ID = "standard-zoom-mode";
    static final String DEEP_ID = "deep-zoom-mode";
    static final String NAVIGATION_SECTION = "Navigation";
    static final String WIDE_PREVIEW_ID = "wide-preview";
    private static final List<String> SWITCH_TITLES = List.of("Off", "On");
    static final String WIDE_PREVIEW_EXTENT_ID = "wide-preview-extent";
    static final String WIDE_PREVIEW_QUALITY_ID = "wide-preview-quality";
    private static final List<Double> EXTENTS = List.of(1.5, 2.0, 3.0, 4.0);
    private static final List<Integer> PIXEL_STEPS = List.of(16, 8, 4, 2);
    private static final int FIELD_COUNT = 5;
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

    /** Everything the window edits: scene antialiasing and the wide-preview preference. */
    record Values(AntialiasSettings antialiasing, boolean widePreview, double extent, int pixelStep) {
        Values(AntialiasSettings antialiasing, boolean widePreview) {
            this(antialiasing, widePreview, WidePreviewSettings.DEFAULT.extent(),
                    WidePreviewSettings.DEFAULT.pixelStep());
        }

        Values(AntialiasSettings antialiasing, WidePreviewSettings preview) {
            this(antialiasing, preview.enabled(), preview.extent(), preview.pixelStep());
        }

        WidePreviewSettings previewSettings() {
            return new WidePreviewSettings(widePreview, extent, pixelStep,
                    WidePreviewSettings.DEFAULT.sampleLimit(), WidePreviewSettings.DEFAULT.workMillis());
        }

        Values {
            Objects.requireNonNull(antialiasing);
            if (!EXTENTS.contains(extent) || !PIXEL_STEPS.contains(pixelStep))
                throw new IllegalArgumentException("Unsupported preview size or quality");
        }

        static Values defaults() {
            return new Values(new AntialiasSettings(), WidePreviewSettings.DEFAULT.enabled());
        }
    }

    private SettingsWindow() {}

    static List<ChoiceField> fields(Values current) {
        AntialiasSettings antialiasing = current.antialiasing();
        return List.of(
                new ChoiceField(RENDER_SECTION, STANDARD_ID, "Standard zoom",
                        "Base only skips antialiasing. Base, then antialiasing smooths the visible frame "
                                + "afterwards. Antialiased display shows only finished antialiased tiles.",
                        MODE_TITLES, index(antialiasing.renderMode())),
                new ChoiceField(RENDER_SECTION, DEEP_ID, "Deep zoom",
                        "Used while Mandelbrot or Julia renders in deep zoom. Deep zoom antialiasing is "
                                + "precise and can take much longer than the base render.",
                        MODE_TITLES, index(antialiasing.deepZoomRenderMode())),
                new ChoiceField(NAVIGATION_SECTION, WIDE_PREVIEW_ID, "Wide preview",
                        "While you pan or zoom out, fills the newly exposed edges with a coarse preview "
                                + "calculated in the background beyond the window. It uses extra CPU time "
                                + "and memory and never replaces the final image or export.",
                        SWITCH_TITLES, current.widePreview() ? 1 : 0),
                new ChoiceField(NAVIGATION_SECTION, WIDE_PREVIEW_EXTENT_ID, "Preview area",
                        "Width and height relative to the window. Larger areas cover longer pans and use more work.",
                        List.of("1.5× window", "2× window", "3× window", "4× window"),
                        EXTENTS.indexOf(current.extent())),
                new ChoiceField(NAVIGATION_SECTION, WIDE_PREVIEW_QUALITY_ID, "Preview quality",
                        "Physical pixels per sample. Smaller steps give finer previews. The sample and time "
                                + "budgets can limit the achieved quality and coverage.",
                        List.of("Low (16 px)", "Standard (8 px)", "High (4 px)", "Very high (2 px)"),
                        PIXEL_STEPS.indexOf(current.pixelStep())));
    }

    /** Selected index of every field, in {@link #fields} order, for the default settings. */
    static int[] defaultSelection() {
        return fields(Values.defaults()).stream().mapToInt(ChoiceField::selected).toArray();
    }

    /** Maps the selected index of every field, in {@link #fields} order, to settings. */
    static Values selection(int... selected) {
        if (selected.length != FIELD_COUNT) {
            throw new IllegalArgumentException("Expected " + FIELD_COUNT + " settings, got " + selected.length);
        }
        return new Values(new AntialiasSettings(MODES.get(selected[0]), MODES.get(selected[1])),
                SWITCH_TITLES.get(selected[2]).equals("On"), EXTENTS.get(selected[3]), PIXEL_STEPS.get(selected[4]));
    }

    /** Position of {@code mode} in the mode drop-down lists. */
    static int index(InteractiveRenderMode mode) {
        return MODES.indexOf(Objects.requireNonNull(mode));
    }
}
