package com.shangin.fractal.math;

import java.math.BigDecimal;
import java.util.Objects;

/** An immutable complex coordinate whose components are not limited to hardware precision. */
public record PreciseComplex(BigDecimal real, BigDecimal imaginary) {

    public PreciseComplex {
        real = canonical(Objects.requireNonNull(real, "Real component must not be null"));
        imaginary = canonical(Objects.requireNonNull(
                imaginary, "Imaginary component must not be null"));
    }

    public PreciseComplex(String real, String imaginary) {
        this(new BigDecimal(real), new BigDecimal(imaginary));
    }

    public static PreciseComplex of(double real, double imaginary) {
        if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
            throw new IllegalArgumentException("Complex components must be finite");
        }
        return new PreciseComplex(BigDecimal.valueOf(real), BigDecimal.valueOf(imaginary));
    }

    public String realText() {
        return real.toString();
    }

    public String imaginaryText() {
        return imaginary.toString();
    }

    private static BigDecimal canonical(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
}
