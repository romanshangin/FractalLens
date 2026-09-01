package com.shangin.fractal.ui;

import com.shangin.fractal.render.PixelShift;
import com.shangin.fractal.render.ValidityMask;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Owns the writable PixelBuffer and backing ARGB array for one surface frame. */
public class SurfaceBuffer {

    private final int width;
    private final int height;

    private final IntBuffer intBuffer;
    private final PixelBuffer<IntBuffer> pixelBuffer;
    private final WritableImage image;

    SurfaceBuffer(int width, int height) {
        this.width = width;
        this.height = height;

        intBuffer = IntBuffer.allocate(width * height);

        pixelBuffer = new PixelBuffer<>(width, height, intBuffer, PixelFormat.getIntArgbPreInstance());

        image = new WritableImage(pixelBuffer);
    }

    boolean matches(int width, int height) {
        return this.width == width && this.height == height;
    }

    public void clear() {
        Arrays.fill(intBuffer.array(), 0);

        pixelBuffer.updateBuffer(pixelBuffer -> null);
    }

    /** Clears provisional colors while keeping pixels confirmed by the supplied mask. */
    void clearExcept(ValidityMask validity) {
        Objects.requireNonNull(validity);

        if (validity.width() != width || validity.height() != height) {
            throw new IllegalArgumentException("Validity dimensions must match the surface");
        }

        RefinedPixelRetention.clearUnconfirmed(
                intBuffer.array(), width, height, validity);

        pixelBuffer.updateBuffer(pixelBuffer -> null);
    }

    public void update() {
        pixelBuffer.updateBuffer(
                ignored -> null
        );
    }

    IntBuffer intBuffer() {
        return intBuffer;
    }

    PixelBuffer<IntBuffer> pixelBuffer() {
        return pixelBuffer;
    }

    WritableImage image() {
        return image;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Copies the overlapping pixels after a shift and returns the dirty rectangle. */
    public Rectangle2D copyShiftedFrom(
            SurfaceBuffer source,
            PixelShift shift
    ) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(shift);

        if (source.width() != width
                || source.height() != height) {
            throw new IllegalArgumentException(
                    "SurfaceBuffer dimensions must match"
            );
        }

        if (source == this) {
            throw new IllegalArgumentException(
                    "Source and target buffers must be different"
            );
        }

        int sourceXFrom =
                Math.max(0, -shift.dx());

        int sourceXTo =
                Math.min(
                        width,
                        width - shift.dx()
                );

        int sourceYFrom =
                Math.max(0, -shift.dy());

        int sourceYTo =
                Math.min(
                        height,
                        height - shift.dy()
                );

        if (sourceXFrom >= sourceXTo
                || sourceYFrom >= sourceYTo) {
            return null;
        }

        int copyWidth =
                sourceXTo - sourceXFrom;

        IntBuffer sourceBuffer =
                source.intBuffer();

        IntBuffer targetBuffer =
                intBuffer();

        for (int sourceY = sourceYFrom;
             sourceY < sourceYTo;
             sourceY++) {

            int targetY =
                    sourceY + shift.dy();

            int sourceIndex =
                    sourceY * width
                            + sourceXFrom;

            int targetIndex =
                    targetY * width
                            + sourceXFrom
                            + shift.dx();

            IntBuffer sourceRow =
                    sourceBuffer.duplicate();

            sourceRow.position(sourceIndex);
            sourceRow.limit(
                    sourceIndex + copyWidth
            );

            IntBuffer targetRow =
                    targetBuffer.duplicate();

            targetRow.position(targetIndex);

            targetRow.put(sourceRow);
        }

        return new Rectangle2D(
                sourceXFrom + shift.dx(),
                sourceYFrom + shift.dy(),
                copyWidth,
                sourceYTo - sourceYFrom
        );
    }

    /** Shifts pixels within this buffer and clears the newly exposed bands. */
    public boolean shiftInPlace(PixelShift shift) {
        Objects.requireNonNull(shift);

        int dx = shift.dx();
        int dy = shift.dy();

        if (dx <= -width || dx >= width || dy <= -height || dy >= height) {
            clear();
            return false;
        }

        int sourceX = Math.max(0, -dx);
        int targetX = Math.max(0, dx);
        int copyWidth = width - Math.abs(dx);
        int sourceY = Math.max(0, -dy);
        int targetY = Math.max(0, dy);
        int copyHeight = height - Math.abs(dy);
        int[] pixels = intBuffer.array();

        if (dy > 0) {
            for (int row = copyHeight - 1; row >= 0; row--) {
                System.arraycopy(
                        pixels,
                        (sourceY + row) * width + sourceX,
                        pixels,
                        (targetY + row) * width + targetX,
                        copyWidth
                );
            }
        } else {
            for (int row = 0; row < copyHeight; row++) {
                System.arraycopy(
                        pixels,
                        (sourceY + row) * width + sourceX,
                        pixels,
                        (targetY + row) * width + targetX,
                        copyWidth
                );
            }
        }

        if (dy > 0) {
            Arrays.fill(pixels, 0, dy * width, 0);
        } else if (dy < 0) {
            Arrays.fill(pixels, (height + dy) * width, height * width, 0);
        }

        if (dx != 0) {
            int clearFrom = dx > 0 ? 0 : width + dx;
            int clearTo = dx > 0 ? dx : width;

            for (int y = 0; y < height; y++) {
                Arrays.fill(
                        pixels,
                        y * width + clearFrom,
                        y * width + clearTo,
                        0
                );
            }
        }

        update();
        return true;
    }
}
