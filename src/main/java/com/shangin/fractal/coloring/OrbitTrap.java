package com.shangin.fractal.coloring;

/** Geometric shapes whose nearest orbit approach can drive palette coloring. */
public enum OrbitTrap {
    NONE("Off") {
        @Override
        public double distance(double real, double imaginary) {
            return Double.NaN;
        }
    },
    POINT("Point") {
        @Override
        public double distance(double real, double imaginary) {
            return Math.hypot(real, imaginary);
        }
    },
    CROSS("Cross") {
        @Override
        public double distance(double real, double imaginary) {
            return Math.min(Math.abs(real), Math.abs(imaginary));
        }
    },
    UNIT_CIRCLE("Unit circle") {
        @Override
        public double distance(double real, double imaginary) {
            return Math.abs(Math.hypot(real, imaginary) - 1.0);
        }
    };

    private final String displayName;

    OrbitTrap(String displayName) {
        this.displayName = displayName;
    }

    public abstract double distance(double real, double imaginary);

    @Override
    public String toString() {
        return displayName;
    }
}
