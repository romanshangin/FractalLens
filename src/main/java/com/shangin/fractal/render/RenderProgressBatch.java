package com.shangin.fractal.render;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RenderProgressBatch(
        RenderFrame frame,
        List<RenderRegion> regions
) {
    public RenderProgressBatch {
        Objects.requireNonNull(frame);
        regions = List.copyOf(regions);
    }

    /**
     * Keeps only exact progress outside the scaled previous-frame preview.
     * Covered center samples may still be calculated, but remain visually
     * represented by the reprojection until the exact frame is promoted.
     */
    public RenderProgressBatch outsideApproximateCoverage() {
        Optional<RenderRegion> coverage = frame.request().approximateCoverage();
        if (coverage.isEmpty() || regions.isEmpty()) {
            return this;
        }

        List<RenderRegion> visible = regions.stream()
                .flatMap(region -> subtract(region, coverage.get()).stream())
                .toList();

        return new RenderProgressBatch(frame, visible);
    }

    static List<RenderRegion> subtract(
            RenderRegion region,
            RenderRegion excluded
    ) {
        int regionRight = region.x() + region.width();
        int regionBottom = region.y() + region.height();
        int excludedRight = excluded.x() + excluded.width();
        int excludedBottom = excluded.y() + excluded.height();

        int intersectionLeft = Math.max(region.x(), excluded.x());
        int intersectionTop = Math.max(region.y(), excluded.y());
        int intersectionRight = Math.min(regionRight, excludedRight);
        int intersectionBottom = Math.min(regionBottom, excludedBottom);

        if (intersectionLeft >= intersectionRight
                || intersectionTop >= intersectionBottom) {
            return List.of(region);
        }

        java.util.ArrayList<RenderRegion> remaining = new java.util.ArrayList<>(4);
        addIfNotEmpty(
                remaining,
                region.x(),
                region.y(),
                region.width(),
                intersectionTop - region.y()
        );
        addIfNotEmpty(
                remaining,
                region.x(),
                intersectionBottom,
                region.width(),
                regionBottom - intersectionBottom
        );
        addIfNotEmpty(
                remaining,
                region.x(),
                intersectionTop,
                intersectionLeft - region.x(),
                intersectionBottom - intersectionTop
        );
        addIfNotEmpty(
                remaining,
                intersectionRight,
                intersectionTop,
                regionRight - intersectionRight,
                intersectionBottom - intersectionTop
        );
        return List.copyOf(remaining);
    }

    private static void addIfNotEmpty(
            List<RenderRegion> regions,
            int x,
            int y,
            int width,
            int height
    ) {
        if (width > 0 && height > 0) {
            regions.add(new RenderRegion(x, y, width, height));
        }
    }
}
