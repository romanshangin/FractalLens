package com.shangin.fractal.scene;

import com.shangin.fractal.math.PreciseComplex;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JuliaParametersTest {

    @Test
    void nearestKeepsBinaryRepresentableCoordinatesExactly() {
        var parameters = JuliaParameters.nearest(new PreciseComplex(
                new BigDecimal(-0.7453), new BigDecimal(0.1127)));

        assertEquals(new JuliaParameters(-0.7453, 0.1127), parameters);
    }

    @Test
    void nearestRoundsEachComponentOnceToNearestWithTiesToEven() {
        BigDecimal ulp = new BigDecimal(Math.ulp(1.0));
        BigDecimal half = ulp.divide(BigDecimal.TWO);
        BigDecimal tiny = new BigDecimal("1e-60");

        // 1 + ulp/2 is a tie between 1 and 1 + ulp; ties go to the even significand (1).
        // 1 + 3ulp/2 is a tie between 1 + ulp and 1 + 2ulp; ties go to 1 + 2ulp.
        var ties = JuliaParameters.nearest(new PreciseComplex(
                BigDecimal.ONE.add(half), BigDecimal.ONE.add(ulp).add(half)));
        assertEquals(1.0, ties.real());
        assertEquals(1.0 + 2 * Math.ulp(1.0), ties.imaginary());

        // Digits far below double precision still decide the direction near a tie.
        var aboveTie = JuliaParameters.nearest(new PreciseComplex(
                BigDecimal.ONE.add(half).add(tiny), BigDecimal.ONE.add(half).subtract(tiny).negate()));
        assertEquals(Math.nextUp(1.0), aboveTie.real());
        assertEquals(-1.0, aboveTie.imaginary());
    }

    @Test
    void nearestMatchesCorrectlyRoundedDecimalParsingForDeepCoordinates() {
        String real = "-0.743643887037158704752191506114774";
        String imaginary = "0.131825904205311970493132056385139";

        var parameters = JuliaParameters.nearest(new PreciseComplex(real, imaginary));

        assertEquals(Double.parseDouble(real), parameters.real());
        assertEquals(Double.parseDouble(imaginary), parameters.imaginary());
    }

    @Test
    void nearestNormalizesSignedZeroAndUnderflow() {
        var parameters = JuliaParameters.nearest(new PreciseComplex(
                new BigDecimal("-1e-400"), BigDecimal.ZERO));

        assertEquals(0.0, parameters.real());
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(parameters.real()));
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(parameters.imaginary()));
    }

    @Test
    void nearestRejectsComponentsOutsideTheFiniteDoubleRange() {
        assertThrows(IllegalArgumentException.class, () -> JuliaParameters.nearest(
                new PreciseComplex(new BigDecimal("1e309"), BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> JuliaParameters.nearest(
                new PreciseComplex(BigDecimal.ZERO, new BigDecimal("-1e309"))));
        assertThrows(NullPointerException.class, () -> JuliaParameters.nearest(null));
    }
}
