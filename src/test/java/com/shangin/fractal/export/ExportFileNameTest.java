package com.shangin.fractal.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportFileNameTest {

    @Test
    void includesNormalizedNamesAndApproximateComplexCenter() {
        assertEquals(
                "mandelbrot_ice_-0.743643887037_+0.131825904205i.png",
                ExportFileName.create(
                        "Mandelbrot",
                        "Ice",
                        -0.743643887037151,
                        0.13182590420533
                )
        );
    }

    @Test
    void makesNamesPortable() {
        assertEquals(
                "julia_set_blue_gold_0.00000000000_+0.00000000000i.png",
                ExportFileName.create(" Julia Set ", "Blue / Gold", 0.0, 0.0)
        );
    }
}
