package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.IterationSettings;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Exact coordinates supplied with the Julia pixelation report. */
public final class ReportedJuliaFixture {
    public static Viewport viewport() throws IOException {
        try (var input = ReportedJuliaFixture.class.getResourceAsStream("/julia/reported-pixelation.txt")) {
            if (input == null) throw new IOException("Missing Julia regression fixture");
            String[] lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R");
            String real = lines[0].substring(lines[0].indexOf(':') + 1).strip();
            String imaginary = lines[1].substring(lines[1].indexOf(':') + 1).strip();
            BigDecimal zoom = new BigDecimal(lines[2].substring(lines[2].indexOf(':') + 1).strip());
            BigDecimal scale = new BigDecimal("2.4").divide(zoom, new MathContext(64));
            return new Viewport(real, imaginary, scale.toString());
        }
    }

    public static RenderJob job(int width, int height) throws IOException {
        Viewport viewport = viewport();
        return new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE), viewport,
                width, height, new IterationSettings().maxIterations(new BigDecimal("2.4"), viewport.scaleExact()));
    }
}
