package com.shangin.fractal.ui;

import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.IntBuffer;
import java.util.Arrays;

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
}