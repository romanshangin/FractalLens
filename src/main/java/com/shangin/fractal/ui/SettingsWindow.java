package com.shangin.fractal.ui;

import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.WidePreviewSettings;

import java.util.List;
import java.util.Objects;

/**
 * Application Settings window shared by the AppKit sheet and the JavaFX form.
 * Settings are grouped into sections of text and choice fields. Render Settings belong
 * to the scene; Navigation is an application preference.
 */
final class SettingsWindow {
    static final String TITLE = "Settings";
    static final String MENU_TITLE = "Settings…";
    static final String HINT = "Render settings apply to the current scene and are saved with it. "
            + "Navigation settings apply to the application. PNG export always uses adaptive antialiasing.";
    static final String RESET_TITLE = "Reset to Defaults";
    static final String ITERATION_ERROR = "Enter a positive base count and a non-negative zoom increment.";
    static final String RENDER_SECTION = "Render Settings";
    static final String BASE_ITERATIONS_ID = "iteration-base";
    static final String ZOOM_ITERATIONS_ID = "iteration-zoom";
    static final String STANDARD_ID = "standard-zoom-mode";
    static final String DEEP_ID = "deep-zoom-mode";
    static final String NAVIGATION_SECTION = "Navigation";
    static final String WIDE_PREVIEW_ID = "wide-preview";
    private static final List<String> SWITCH_TITLES = List.of("Off", "On");
    static final String WIDE_PREVIEW_EXTENT_ID = "wide-preview-extent";
    static final String WIDE_PREVIEW_QUALITY_ID = "wide-preview-quality";
    private static final List<Double> EXTENTS = List.of(1.5, 2.0, 3.0, 4.0);
    private static final List<Integer> PIXEL_STEPS = List.of(16, 8, 4, 2);
    private static final int CHOICE_COUNT = 5;
    private static final int TEXT_COUNT = 2;
    /** Display order, from the least to the most antialiased presentation. */
    private static final List<InteractiveRenderMode> MODES = List.of(
            InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.FAST, InteractiveRenderMode.REFINED);
    private static final List<String> MODE_TITLES = MODES.stream().map(InteractiveRenderMode::toString).toList();

    /** One labelled control of the window. */
    sealed interface Field permits ChoiceField, TextField {
        String section();
        String id();
        String label();
        String hint();
    }

    /** One exclusive choice; {@code selected} indexes {@code choices}. */
    record ChoiceField(String section, String id, String label, String hint, List<String> choices, int selected)
            implements Field {
        ChoiceField {
            Objects.requireNonNull(section);
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(hint);
            choices = List.copyOf(choices);
            if (choices.isEmpty()) throw new IllegalArgumentException("A choice field needs choices");
            Objects.checkIndex(selected, choices.size());
        }
    }

    /** One free-text draft, validated by {@link #invalidText} before it is applied. */
    record TextField(String section, String id, String label, String hint, String text, String defaultText)
            implements Field {
        TextField {
            Objects.requireNonNull(section);
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(hint);
            Objects.requireNonNull(text);
            Objects.requireNonNull(defaultText);
        }
    }

    /** Everything the window edits: scene iterations and antialiasing, and the wide-preview preference. */
    record Values(IterationSettings iterations, AntialiasSettings antialiasing, boolean widePreview,
                  double extent, int pixelStep) {
        /** Values with default iterations and preview size and quality. */
        Values(AntialiasSettings antialiasing, boolean widePreview) {
            this(new IterationSettings(), antialiasing, widePreview);
        }

        /** Values with default iterations. */
        Values(AntialiasSettings antialiasing, boolean widePreview, double extent, int pixelStep) {
            this(new IterationSettings(), antialiasing, widePreview, extent, pixelStep);
        }

        Values(IterationSettings iterations, AntialiasSettings antialiasing, boolean widePreview) {
            this(iterations, antialiasing, widePreview, WidePreviewSettings.DEFAULT.extent(),
                    WidePreviewSettings.DEFAULT.pixelStep());
        }

        Values(IterationSettings iterations, AntialiasSettings antialiasing, WidePreviewSettings preview) {
            this(iterations, antialiasing, preview.enabled(), preview.extent(), preview.pixelStep());
        }

        WidePreviewSettings previewSettings() {
            return new WidePreviewSettings(widePreview, extent, pixelStep,
                    WidePreviewSettings.DEFAULT.sampleLimit(), WidePreviewSettings.DEFAULT.workMillis());
        }

        Values {
            Objects.requireNonNull(iterations);
            Objects.requireNonNull(antialiasing);
            if (!EXTENTS.contains(extent) || !PIXEL_STEPS.contains(pixelStep))
                throw new IllegalArgumentException("Unsupported preview size or quality");
        }

        static Values defaults() {
            return new Values(new AntialiasSettings(), WidePreviewSettings.DEFAULT.enabled());
        }
    }

    private SettingsWindow() {}

    static List<Field> fields(Values current) {
        AntialiasSettings antialiasing = current.antialiasing();
        IterationSettings iterations = current.iterations();
        return List.of(
                new TextField(RENDER_SECTION, BASE_ITERATIONS_ID, "Base iterations",
                        "Iteration limit at the initial view. Higher limits reveal more detail near the set "
                                + "boundary and take longer.",
                        Integer.toString(iterations.baseIterations()),
                        Integer.toString(IterationSettings.DEFAULT_BASE_ITERATIONS)),
                new TextField(RENDER_SECTION, ZOOM_ITERATIONS_ID, "Additional per zoom level",
                        "Iterations added each time the zoom doubles, so deep views keep their detail.",
                        Integer.toString(iterations.iterationsPerZoomLevel()),
                        Integer.toString(IterationSettings.DEFAULT_ITERATIONS_PER_ZOOM_LEVEL)),
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
                        "Target physical pixels per sample. Smaller steps give finer previews. The preview memory "
                                + "budget may require a coarser step; the badge tooltip shows the effective step. "
                                + "Partial previews continue calculating while the application is idle.",
                        List.of("Low (16 px)", "Standard (8 px)", "High (4 px)", "Very high (2 px)"),
                        PIXEL_STEPS.indexOf(current.pixelStep())));
    }

    /** The choice fields of {@code fields}, in order. */
    static List<ChoiceField> choiceFields(List<Field> fields) {
        return fields.stream().filter(ChoiceField.class::isInstance).map(ChoiceField.class::cast).toList();
    }

    /** The text fields of {@code fields}, in order. */
    static List<TextField> textFields(List<Field> fields) {
        return fields.stream().filter(TextField.class::isInstance).map(TextField.class::cast).toList();
    }

    /** Selected index of every choice field, in {@link #fields} order, for the default settings. */
    static int[] defaultSelection() {
        return choiceFields(fields(Values.defaults())).stream().mapToInt(ChoiceField::selected).toArray();
    }

    /** Default draft of every text field, in {@link #fields} order. */
    static List<String> defaultTexts() {
        return textFields(fields(Values.defaults())).stream().map(TextField::text).toList();
    }

    /** Returns zero for valid text drafts, otherwise the one-based index of the first invalid text field. */
    static int invalidText(List<String> texts) {
        if (texts.size() != TEXT_COUNT) {
            throw new IllegalArgumentException("Expected " + TEXT_COUNT + " text settings, got " + texts.size());
        }
        if (!validCount(texts.get(0), true)) return 1;
        return validCount(texts.get(1), false) ? 0 : 2;
    }

    /**
     * Maps the selected index of every choice field and the draft of every text field,
     * each in {@link #fields} order, to settings.
     */
    static Values selection(int[] selected, List<String> texts) {
        if (selected.length != CHOICE_COUNT) {
            throw new IllegalArgumentException("Expected " + CHOICE_COUNT + " settings, got " + selected.length);
        }
        if (invalidText(texts) != 0) throw new IllegalArgumentException(ITERATION_ERROR);
        return new Values(
                new IterationSettings(Integer.parseInt(texts.get(0).trim()), Integer.parseInt(texts.get(1).trim())),
                new AntialiasSettings(MODES.get(selected[0]), MODES.get(selected[1])),
                SWITCH_TITLES.get(selected[2]).equals("On"), EXTENTS.get(selected[3]), PIXEL_STEPS.get(selected[4]));
    }

    /** Position of {@code mode} in the mode drop-down lists. */
    static int index(InteractiveRenderMode mode) {
        return MODES.indexOf(Objects.requireNonNull(mode));
    }

    /** Accessible help for an invalid text field, by its one-based index. */
    static String textHelp(int index) {
        return index == 1 ? "Enter a positive whole number." : "Enter zero or a positive whole number.";
    }

    private static boolean validCount(String draft, boolean positive) {
        String text = draft.trim();
        if (!text.matches("\\+?[0-9]+")) return false;
        try {
            int value = Integer.parseInt(text);
            return positive ? value > 0 : value >= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
