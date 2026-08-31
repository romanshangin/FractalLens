package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure palette-stop operations shared by the JavaFX editor and unit tests. */
final class PaletteStopOperations {

    private PaletteStopOperations() {}

    static ColorStop createAddedStop(List<ColorStop> sourceStops) {
        if (sourceStops.size() < 2) {
            throw new IllegalArgumentException("At least two stops are required");
        }
        List<ColorStop> stops = new ArrayList<>(sourceStops);
        stops.sort(Comparator.comparingDouble(ColorStop::position));
        double position = 0.5;
        int color = 0xFF808080;
        double widest = -1.0;
        for (int index = 0; index < stops.size() - 1; index++) {
            ColorStop left = stops.get(index);
            ColorStop right = stops.get(index + 1);
            double width = right.position() - left.position();
            if (width > widest) {
                widest = width;
                position = (left.position() + right.position()) / 2.0;
                color = mix(left.color(), right.color());
            }
        }
        return new ColorStop(position, color);
    }

    private static int mix(int first, int second) {
        int result = 0;
        for (int shift : new int[]{0, 8, 16, 24}) {
            int channel = (((first >>> shift) & 0xFF) + ((second >>> shift) & 0xFF)) / 2;
            result |= channel << shift;
        }
        return result;
    }
}
