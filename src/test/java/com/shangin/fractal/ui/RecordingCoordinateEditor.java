package com.shangin.fractal.ui;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** A Go to Coordinates editor that records applied changes instead of moving a view. */
final class RecordingCoordinateEditor implements CoordinateEditor {
    final List<CoordinateTarget> applied = new ArrayList<>();
    int submits;
    boolean submittedOffFxThread;
    private final Predicate<CoordinateTarget> reachable;

    RecordingCoordinateEditor(Predicate<CoordinateTarget> reachable) {
        this.reachable = reachable;
    }

    @Override public int invalidField(CoordinateText shown, String real, String imaginary, String zoom) {
        int invalid = CoordinateTarget.invalidField(shown, real, imaginary, zoom);
        if (invalid != 0) return invalid;
        CoordinateTarget target = CoordinateTarget.parse(shown, real, imaginary, zoom);
        return target.unchanged() || reachable.test(target) ? 0 : 3;
    }

    @Override public int submit(CoordinateText shown, String real, String imaginary, String zoom) {
        submits++;
        submittedOffFxThread |= !Platform.isFxApplicationThread();
        int invalid = invalidField(shown, real, imaginary, zoom);
        if (invalid == 0) {
            CoordinateTarget target = CoordinateTarget.parse(shown, real, imaginary, zoom);
            if (!target.unchanged()) applied.add(target);
        }
        return invalid;
    }
}
