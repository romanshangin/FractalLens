package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.WidePreviewSettings;

import java.math.BigDecimal;
import java.util.Objects;

/** A separate coarse grid, never used to satisfy a visible-frame request. */
public record WidePreviewPlan(RenderJob job, WidePreviewSettings settings, long physicalStep) {
    public static WidePreviewPlan create(RenderJob visible, WidePreviewSettings settings,
                                         RenderFrame previous) {
        Objects.requireNonNull(visible);
        Objects.requireNonNull(settings);
        long step = settings.pixelStep();
        long columns;
        long rows;
        do {
            columns = Math.max(2, (long) Math.ceil((visible.width() - 1.0) * settings.extent() / step) + 1);
            rows = Math.max(2, (long) Math.ceil((visible.height() - 1.0) * settings.extent() / step) + 1);
            if ((double) columns * rows <= settings.sampleLimit()) break;
            double factor = Math.max(1.01, Math.sqrt((double) columns * rows / settings.sampleLimit()));
            step = Math.max(step + 1, (long) Math.ceil(step * factor));
        } while (true);
        int width = Math.toIntExact(columns);
        int height = Math.toIntExact(rows);
        Viewport view = visible.viewport();
        BigDecimal scale = view.imaginaryUnitsPerPixelExact(visible.height())
                .multiply(BigDecimal.valueOf(step * (height - 1)), view.mathContext());
        Viewport coarse = view.withCalculatedValues(view.center(), scale);
        // Move the reserve in whole coarse cells so small pans retain calculated rows.
        if (previous != null && previous.request().width() == width
                && previous.request().height() == height
                && previous.request().viewport().scaleExact().compareTo(scale) == 0) {
            coarse = coarse.snapToPixelGrid(previous.request().viewport(), width, height);
        }
        return new WidePreviewPlan(new RenderJob(visible.formula(), coarse, width, height,
                Math.min(visible.maxIterations(), settings.iterationLimit())), settings, step);
    }
}
