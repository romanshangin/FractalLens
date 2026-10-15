package com.shangin.fractal.export;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.Objects;

/** Writes a premultiplied ARGB render buffer as a PNG image. */
public final class PngExporter {

    private PngExporter() {}

    public static void write(
            Path path,
            int width,
            int height,
            int[] premultipliedArgb
    ) throws IOException {
        write(path, width, height, premultipliedArgb, () -> false);
    }

    static void write(Path path, int width, int height, int[] premultipliedArgb,
                      BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(premultipliedArgb);

        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }

        if (premultipliedArgb.length != (long) width * height) {
            throw new IllegalArgumentException("Pixel count must match image dimensions");
        }

        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB_PRE
        );

        int[] targetPixels = ((DataBufferInt) image
                .getRaster()
                .getDataBuffer())
                .getData();

        for (int y = 0; y < height; y++) {
            checkCancelled(cancelled);
            System.arraycopy(premultipliedArgb, y * width, targetPixels, y * width, width);
        }

        try (OutputStream output = new FilterOutputStream(Files.newOutputStream(path)) {
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                checkCancelled(cancelled);
                out.write(bytes, offset, length);
            }

            @Override
            public void write(int value) throws IOException {
                checkCancelled(cancelled);
                out.write(value);
            }
        }) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("No PNG image writer is available");
            }
        }
        checkCancelled(cancelled);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }
}
