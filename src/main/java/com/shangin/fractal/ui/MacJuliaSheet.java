package com.shangin.fractal.ui;

import com.shangin.fractal.scene.JuliaParameters;
import javafx.stage.Window;

import java.util.function.Consumer;

/** Native editor using the same finite-double contract as the JavaFX form. */
final class MacJuliaSheet extends MacParameterSheet<JuliaParameters> {
    static MacJuliaSheet show(Window owner, JuliaParameters current,
                              Consumer<JuliaParameters> apply) throws ReflectiveOperationException {
        var defaults = new JuliaParameters();
        var sheet = new MacJuliaSheet(owner, apply);
        sheet.showNative("Julia Parameters", "Set the real and imaginary components of the Julia constant.",
                "Real", "Imaginary", "julia-real", "julia-imaginary",
                Double.toString(current.real()), Double.toString(current.imaginary()),
                Double.toString(defaults.real()), Double.toString(defaults.imaginary()),
                "Enter finite numbers for both Julia components.");
        return sheet;
    }

    private MacJuliaSheet(Window owner, Consumer<JuliaParameters> apply) { super(owner, apply); }

    @Override int invalidField(String first, String second) {
        if (!finite(first)) return 1;
        return finite(second) ? 0 : 2;
    }

    private static boolean finite(String draft) {
        try { return Double.isFinite(Double.parseDouble(draft.trim())); }
        catch (NumberFormatException invalid) { return false; }
    }

    @Override JuliaParameters parse(String first, String second) {
        return new JuliaParameters(Double.parseDouble(first.trim()), Double.parseDouble(second.trim()));
    }
}
