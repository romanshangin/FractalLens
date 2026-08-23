package com.shangin.fractal.coloring;

public interface Palette {

    int color(double position);

    default int middleColor() {
        return this.color(0.5);
    }
}
