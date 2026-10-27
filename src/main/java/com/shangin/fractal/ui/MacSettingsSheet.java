package com.shangin.fractal.ui;

import com.shangin.fractal.scene.AntialiasSettings;
import javafx.stage.Window;

import java.util.Arrays;
import java.util.function.Consumer;

/** Native Settings sheet; the draft is the comma-separated selected index of each pop-up button. */
final class MacSettingsSheet extends MacParameterSheet<AntialiasSettings> {

    static MacSettingsSheet show(Window owner, AntialiasSettings current, Consumer<AntialiasSettings> apply) {
        var sheet = new MacSettingsSheet(owner, apply);
        sheet.showChoicesNative(SettingsWindow.TITLE, SettingsWindow.HINT, SettingsWindow.fields(current),
                SettingsWindow.defaultSelection(), SettingsWindow.RESET_TITLE, "Apply");
        return sheet;
    }

    private MacSettingsSheet(Window owner, Consumer<AntialiasSettings> apply) { super(owner, apply); }

    @Override int invalidField(String first, String second) {
        try {
            parse(first, second);
            return 0;
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            return 1;
        }
    }

    @Override AntialiasSettings parse(String first, String second) {
        return SettingsWindow.selection(Arrays.stream(first.split(",", -1)).mapToInt(Integer::parseInt).toArray());
    }
}
