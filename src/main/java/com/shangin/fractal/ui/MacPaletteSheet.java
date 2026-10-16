package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.GradientPalette;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** AppKit color wells and editable positions; no draft is applied before validation. */
final class MacPaletteSheet extends MacParameterSheet<List<ColorStop>> {
    private MacPaletteSheet(Window owner, Consumer<List<ColorStop>> apply) { super(owner, apply); }

    static MacPaletteSheet show(Window owner, List<ColorStop> stops, Consumer<List<ColorStop>> apply) {
        var sheet = new MacPaletteSheet(owner, apply);
        sheet.showFormNative("Edit Palette", "Choose colors and positions along the gradient, from 0 to 1.",
                "Position", "Color", "palette-position", "palette-color",
                stops.stream().map(stop -> Double.toString(stop.position())).collect(Collectors.joining("\n")),
                stops.stream().map(stop -> Integer.toString(stop.color())).collect(Collectors.joining("\n")),
                "Enter a position from 0 to 1 for every color stop.", "Apply", true);
        return sheet;
    }

    @Override int invalidField(String positions, String colors) {
        String[] drafts = positions.split("\n", -1);
        if (drafts.length < 2 || drafts.length != colors.split("\n", -1).length) return 1;
        for (int i = 0; i < drafts.length; i++) {
            try { position(drafts[i]); }
            catch (NumberFormatException invalid) { return i + 1; }
        }
        return 0;
    }

    private static double position(String draft) {
        var value = new BigDecimal(draft.trim());
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)
            throw new NumberFormatException("Position must be from 0 to 1");
        return value.doubleValue();
    }

    @Override List<ColorStop> parse(String positions, String colors) {
        String[] drafts = positions.split("\n", -1);
        String[] argb = colors.split("\n", -1);
        if (invalidField(positions, colors) != 0) throw new IllegalArgumentException("Invalid palette drafts");
        var stops = new ArrayList<ColorStop>();
        for (int i = 0; i < drafts.length; i++)
            stops.add(new ColorStop(position(drafts[i]), Integer.parseInt(argb[i])));
        return List.copyOf(stops);
    }

    @SuppressWarnings("unused") // JNI: preview uses the same OKLab interpolation as rendering.
    private int[] preview(String positions, String colors) {
        if (invalidField(positions, colors) != 0) return new int[0];
        var palette = GradientPalette.cached(parse(positions, colors));
        int[] pixels = new int[400];
        for (int i = 0; i < pixels.length; i++) pixels[i] = palette.color(i / (pixels.length - 1.0));
        return pixels;
    }

    @SuppressWarnings("unused") // JNI: share the existing add-stop operation with the fallback.
    private String addedStop(String positions, String colors) {
        if (invalidField(positions, colors) != 0) return "";
        var stop = PaletteStopOperations.createAddedStop(parse(positions, colors));
        return stop.position() + "\n" + stop.color();
    }
}
