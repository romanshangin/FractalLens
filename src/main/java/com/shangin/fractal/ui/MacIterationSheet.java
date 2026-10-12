package com.shangin.fractal.ui;

import com.shangin.fractal.scene.IterationSettings;
import javafx.stage.Window;

import java.util.function.Consumer;

/** Native editor for the two iteration policy values. */
final class MacIterationSheet extends MacParameterSheet<IterationSettings> {
    static MacIterationSheet show(Window owner, IterationSettings current,
                                  Consumer<IterationSettings> apply) throws ReflectiveOperationException {
        var sheet = new MacIterationSheet(owner, apply);
        sheet.showNative("Iteration Settings", "Set the base render budget and how much it grows as you zoom in.",
                "Base iterations", "Additional per zoom level", "iteration-base", "iteration-zoom",
                Integer.toString(current.baseIterations()), Integer.toString(current.iterationsPerZoomLevel()),
                Integer.toString(IterationSettings.DEFAULT_BASE_ITERATIONS),
                Integer.toString(IterationSettings.DEFAULT_ITERATIONS_PER_ZOOM_LEVEL),
                "Enter a positive base count and a non-negative zoom increment.");
        return sheet;
    }

    private MacIterationSheet(Window owner, Consumer<IterationSettings> apply) { super(owner, apply); }

    @Override int invalidField(String first, String second) {
        if (!validCount(first, true)) return 1;
        return validCount(second, false) ? 0 : 2;
    }

    private static boolean validCount(String draft, boolean positive) {
        String text = draft.trim();
        if (!text.matches("\\+?[0-9]+")) return false;
        try {
            int value = Integer.parseInt(text);
            return positive ? value > 0 : value >= 0;
        } catch (NumberFormatException invalid) { return false; }
    }

    @Override IterationSettings parse(String first, String second) {
        return new IterationSettings(Integer.parseInt(first.trim()), Integer.parseInt(second.trim()));
    }
}
