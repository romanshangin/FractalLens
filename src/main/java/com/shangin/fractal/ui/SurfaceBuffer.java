package com.shangin.fractal.ui;

import com.shangin.fractal.render.PixelShift;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Objects;

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
}