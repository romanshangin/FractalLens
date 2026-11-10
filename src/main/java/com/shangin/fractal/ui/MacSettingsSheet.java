package com.shangin.fractal.ui;

import javafx.stage.Window;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Native Settings sheet. The first draft is the comma-separated selected index of each
 * pop-up button; the second joins the text-field drafts with the unit separator.
 */
final class MacSettingsSheet extends MacParameterSheet<SettingsWindow.Values> {

    static MacSettingsSheet show(Window owner, SettingsWindow.Values current, Consumer<SettingsWindow.Values> apply) {
        var sheet = new MacSettingsSheet(owner, apply);
        sheet.showChoicesNative(SettingsWindow.TITLE, SettingsWindow.HINT, SettingsWindow.fields(current),
                SettingsWindow.defaultSelection(), SettingsWindow.defaultTexts(), SettingsWindow.ITERATION_ERROR,
                SettingsWindow.RESET_TITLE, "Apply");
        return sheet;
    }

    private MacSettingsSheet(Window owner, Consumer<SettingsWindow.Values> apply) { super(owner, apply); }

    /** Returns zero for valid drafts, otherwise the one-based index of the text field to correct. */
    @Override int invalidField(String first, String second) {
        try {
            int invalid = SettingsWindow.invalidText(texts(second));
            if (invalid != 0) return invalid;
            parse(first, second);
            return 0;
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            return 1;
        }
    }

    @Override SettingsWindow.Values parse(String first, String second) {
        return SettingsWindow.selection(Arrays.stream(first.split(",", -1)).mapToInt(Integer::parseInt).toArray(),
                texts(second));
    }

    private static List<String> texts(String drafts) {
        return List.of(drafts.split(THIRD_DRAFT_SEPARATOR, -1));
    }
}
