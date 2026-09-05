package com.shangin.fractal.controller;

/** Status of the latest viewport render, including its refinement pass. */
public record RenderStatus(State state, long elapsedNanos) {
    public enum State { IDLE, RENDERING, COMPLETE, CANCELLED, FAILED }
}
