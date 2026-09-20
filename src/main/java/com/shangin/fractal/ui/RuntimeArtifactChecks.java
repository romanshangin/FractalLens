package com.shangin.fractal.ui;

/** Narrow packaging probe for native resources that are otherwise loaded lazily. */
public final class RuntimeArtifactChecks {

    private RuntimeArtifactChecks() {
    }

    public static boolean nativeMenuAvailable() {
        return MacContextMenu.isAvailable();
    }
}
