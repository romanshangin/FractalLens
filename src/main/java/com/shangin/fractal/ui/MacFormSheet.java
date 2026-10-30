package com.shangin.fractal.ui;

import com.shangin.fractal.export.AdaptivePngExportService;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.render.RenderTarget;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;

/** Form editors retaining Java parsing and exact decimal coordinates. */
final class MacFormSheet<T> extends MacParameterSheet<T> {
    private final BiFunction<String, String, T> parser;
    private final ToIntBiFunction<String, String> validator;
    private final boolean dimensions;

    private MacFormSheet(Window owner, Consumer<T> apply, BiFunction<String, String, T> parser,
                         ToIntBiFunction<String, String> validator, boolean dimensions) {
        super(owner, apply);
        this.parser = parser;
        this.validator = validator;
        this.dimensions = dimensions;
    }

    /** Real, imaginary and zoom; the imaginary and zoom drafts arrive packed in the second draft. */
    static MacFormSheet<CoordinateTarget> coordinates(Window owner, PreciseComplex center, BigDecimal zoom,
                                                      String hint, Predicate<CoordinateTarget> reachable,
                                                      Consumer<CoordinateTarget> apply) {
        BiFunction<String, String, CoordinateTarget> parser = (real, packed) -> {
            String[] drafts = packed.split(THIRD_DRAFT_SEPARATOR, 2);
            if (drafts.length != 2) throw new IllegalArgumentException("Missing zoom draft");
            return CoordinateTarget.parse(real, drafts[0], drafts[1], zoom);
        };
        var sheet = new MacFormSheet<>(owner, apply, parser, (real, packed) -> {
            String[] drafts = packed.split(THIRD_DRAFT_SEPARATOR, 2);
            return FractalDialogs.invalidCoordinateField(real, drafts[0],
                    drafts.length == 2 ? drafts[1] : "", zoom, reachable);
        }, false);
        sheet.showFormNative("Go to Coordinates", hint, "Real", "Imaginary", "Zoom",
                "coordinate-real", "coordinate-imaginary", "coordinate-zoom",
                center.realText(), center.imaginaryText(), CoordinateTarget.zoomText(zoom),
                FractalDialogs.COORDINATE_ERROR, "Go");
        return sheet;
    }

    static MacFormSheet<RenderTarget> export(Window owner, RenderTarget target, Consumer<RenderTarget> apply) {
        var sheet = new MacFormSheet<>(owner, apply, MacFormSheet::parseTarget, null, true);
        sheet.showFormNative("Export PNG", "Render a scene snapshot at independent dimensions. "
                        + "The center and vertical span stay fixed; width changes the horizontal span. "
                        + "Adaptive antialiasing is included. Cancel Export PNG stops the export. "
                        + "PNG uses up to roughly width × height × 4 bytes before compression.",
                "Width (pixels)", "Height (pixels)", "export-width", "export-height",
                Integer.toString(target.width()), Integer.toString(target.height()),
                "Enter whole dimensions of at least 2 within the export size limit.", "Export…", false);
        return sheet;
    }

    static RenderTarget parseTarget(String width, String height) {
        var target = new RenderTarget(Integer.parseInt(width.trim()), Integer.parseInt(height.trim()));
        AdaptivePngExportService.validateTarget(target);
        return target;
    }

    @Override int invalidField(String first, String second) {
        if (validator != null) return validator.applyAsInt(first, second);
        try { parser.apply(first, second); return 0; }
        catch (IllegalArgumentException invalid) {
            try {
                if (Integer.parseInt(first.trim()) < 2) return 1;
            } catch (NumberFormatException error) { return 1; }
            return 2;
        }
    }

    @Override String estimate(String width, String height) {
        if (!dimensions) return "";
        try {
            var target = parseTarget(width, height);
            double mib = ((long) target.width() * target.height() * 4.0 + target.height()) / (1024 * 1024);
            return String.format(java.util.Locale.ROOT,
                    "PNG size: up to about %.1f MiB before compression. The saved file depends on image detail.", mib);
        } catch (IllegalArgumentException invalid) { return "Enter valid dimensions to estimate the PNG size."; }
    }

    @Override T parse(String first, String second) { return parser.apply(first, second); }
}
